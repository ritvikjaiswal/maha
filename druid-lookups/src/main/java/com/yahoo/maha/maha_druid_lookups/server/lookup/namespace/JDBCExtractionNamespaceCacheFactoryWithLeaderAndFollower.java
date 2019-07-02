// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.maha_druid_lookups.server.lookup.namespace;

import com.google.inject.Inject;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.metamx.common.logger.Logger;
import com.metamx.emitter.service.ServiceEmitter;
import com.yahoo.maha.maha_druid_lookups.query.lookup.DecodeConfig;
import com.yahoo.maha.maha_druid_lookups.query.lookup.namespace.JDBCExtractionNamespace;
import com.yahoo.maha.maha_druid_lookups.query.lookup.namespace.JDBCExtractionNamespaceWithLeaderAndFollower;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.entity.ProtobufSchemaFactory;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.entity.RowMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.DefaultMapper;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.util.TimestampMapper;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 *
 */
public class JDBCExtractionNamespaceCacheFactoryWithLeaderAndFollower
        extends JDBCExtractionNamespaceCacheFactory {
    private static final Logger LOG = new Logger(JDBCExtractionNamespaceCacheFactoryWithLeaderAndFollower.class);
    private static final String COMMA_SEPARATOR = ",";
    private static final String FIRST_TIME_CACHING_WHERE_CLAUSE = " WHERE LAST_UPDATED <= :lastUpdatedTimeStamp";
    private static final String SUBSEQUENT_CACHING_WHERE_CLAUSE = " WHERE LAST_UPDATED > :lastUpdatedTimeStamp";
    private static final int FETCH_SIZE = 10000;
    private final ConcurrentMap<String, DBI> dbiCache = new ConcurrentHashMap<>();

    private KafkaProducer<String, byte[]> kafkaProducer = null;
    private KafkaConsumer<String, byte[]> kafkaConsumer = null;

    private Properties kafkaProperties;

    @Inject
    ProtobufSchemaFactory protobufSchemaFactory;

    @Inject
    LookupService lookupService;
    @Inject
    ServiceEmitter emitter;


    public Callable<String> getCachePopulator(
            final String id,
            final JDBCExtractionNamespaceWithLeaderAndFollower extractionNamespace,
            final String lastVersion,
            final Map<String, List<String>> cache
    ) {
        Objects.requireNonNull(kafkaProperties, "Must first define kafkaProperties to create a JDBC -> Kafka link.");
        Objects.requireNonNull(protobufSchemaFactory, "Kafka needs a Protobuf for the JDBC input.");
        return getCachePopulator(id, extractionNamespace, lastVersion, cache, kafkaProperties, protobufSchemaFactory);
    }

    public void setKafkaProperties(Properties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    public void setProtobufSchemaFactory(ProtobufSchemaFactory protobufSchemaFactory) {
        this.protobufSchemaFactory = protobufSchemaFactory;
    }

    public Callable<String> getCachePopulator(
            final String id,
            final JDBCExtractionNamespaceWithLeaderAndFollower extractionNamespace,
            final String lastVersion,
            final Map<String, List<String>> cache,
            final Properties kafkaProperties,
            final ProtobufSchemaFactory protobufSchemaFactory
    ) {
        final long lastCheck = lastVersion == null ? Long.MIN_VALUE / 2 : Long.parseLong(lastVersion);
        if (!extractionNamespace.isCacheEnabled() && !extractionNamespace.getIsLeader()) {
            return nonCacheEnabledCall(lastCheck);
        }
        final Timestamp lastDBUpdate = lastUpdates(id, extractionNamespace);
        if (Objects.nonNull(lastDBUpdate) && lastDBUpdate.getTime() <= lastCheck) {
            return new Callable<String>() {
                @Override
                public String call() {
                    extractionNamespace.setPreviousLastUpdateTimestamp(lastDBUpdate);
                    return lastVersion;
                }
            };
        }
        if(extractionNamespace.getIsLeader()) {
            return doLeaderOperations(id, extractionNamespace, lastVersion, cache, kafkaProperties, protobufSchemaFactory, lastDBUpdate);
        } else {
            return doFollowerOperations(id, extractionNamespace, lastVersion, cache, kafkaProperties, protobufSchemaFactory);
        }
    }

    private List<Map<String, Object>> populateRowListFromJDBC(
            JDBCExtractionNamespaceWithLeaderAndFollower extractionNamespace,
            String query,
            Map<String, List<String>> cache,
            Timestamp lastDBUpdate,
            Handle handle
    ) {
        List<Map<String, Object>> rowList;
        if (extractionNamespace.isFirstTimeCaching()) {
            extractionNamespace.setFirstTimeCaching(false);
            query = String.format("%s %s", query, FIRST_TIME_CACHING_WHERE_CLAUSE);
            rowList = handle.createQuery(query).map(
                    new RowMapper(extractionNamespace, cache))
                    .setFetchSize(FETCH_SIZE)
                    .bind("lastUpdatedTimeStamp", lastDBUpdate)
                    .map(new DefaultMapper())
                    .list();

        } else {
            query = String.format("%s %s", query, SUBSEQUENT_CACHING_WHERE_CLAUSE);
            rowList = handle.createQuery(query).map(
                    new RowMapper(extractionNamespace, cache))
                    .setFetchSize(FETCH_SIZE)
                    .bind("lastUpdatedTimeStamp", extractionNamespace.getPreviousLastUpdateTimestamp())
                    .map(new DefaultMapper())
                    .list();
        }

        return rowList;
    }

    /**
     * Use the active JDBC
     * @param id
     * @param extractionNamespace
     * @param lastVersion
     * @param cache
     * @param kafkaProperties
     * @param protobufSchemaFactory
     * @param lastDBUpdate
     * @return
     */
    public Callable<String> doLeaderOperations(final String id,
                                               final JDBCExtractionNamespaceWithLeaderAndFollower extractionNamespace,
                                               final String lastVersion,
                                               final Map<String, List<String>> cache,
                                               final Properties kafkaProperties,
                                               final ProtobufSchemaFactory protobufSchemaFactory,
                                               final Timestamp lastDBUpdate) {
        LOG.info("Running Kafka Leader - Producer actions on %s.", id);
        kafkaProducer = ensureKafkaProducer(kafkaProperties);
        final String producerKafkaTopic = extractionNamespace.getKafkaTopic();

        return new Callable<String>() {
            @Override
            public String call() {
                final DBI dbi = ensureDBI(id, extractionNamespace);

                LOG.debug("Updating [%s]", id);

                //Call Oracle through JDBC connection
                dbi.withHandle(
                        new HandleCallback<Void>() {
                            @Override
                            public Void withHandle(Handle handle) {

                                Descriptors.Descriptor descriptor = protobufSchemaFactory.getProtobufDescriptor(extractionNamespace.getLookupName());
                                Message.Builder messageBuilder = protobufSchemaFactory.getProtobufMessageBuilder(extractionNamespace.getLookupName());
                                Map<String, Object> map = new HashMap<>();
                                List<Map<String,  Object>> rowList;
                                String query =
                                        String.format(
                                                "SELECT %s FROM %s",
                                                String.join(COMMA_SEPARATOR, extractionNamespace.getColumnList()),
                                                extractionNamespace.getTable()
                                        );

                                rowList = populateRowListFromJDBC(extractionNamespace, query, cache, lastDBUpdate, handle);

                                if(Objects.nonNull(rowList)) {
                                    for(Map<String, Object> row: rowList) {
                                        descriptor.getFields()
                                                .stream()
                                                .forEach(fd -> messageBuilder.setField(fd, String.valueOf(row.get(fd.getName()))));

                                        Message message = messageBuilder.build();
                                        LOG.info("Producing key [%s] val [%s]", extractionNamespace.getTable(), message);
                                        LOG.info("Leader mode enabled on node.  Sending lookup record to Kafka Topic " + producerKafkaTopic);
                                        ProducerRecord<String, byte[]> producerRecord =
                                                new ProducerRecord<>(producerKafkaTopic, extractionNamespace.getTable(), message.toByteArray());
                                        kafkaProducer.send(producerRecord);
                                    }
                                } else {
                                    LOG.info("No query results to return.");
                                }

                                return null;
                            }
                        }
                );

                LOG.info("Finished loading %d values for extractionNamespace[%s]", cache.size(), id);
                extractionNamespace.setPreviousLastUpdateTimestamp(lastDBUpdate);
                return String.format("%d", lastDBUpdate.getTime());
            }
        };


    }

    public Callable<String> doFollowerOperations(final String id,
                                                 final JDBCExtractionNamespaceWithLeaderAndFollower extractionNamespace,
                                                 final String lastVersion,
                                                 final Map<String, List<String>> cache,
                                                 final Properties kafkaProperties,
                                                 final ProtobufSchemaFactory protobufSchemaFactory) {
        LOG.info("Running Kafka Follower - Consumer actions on %s.", id);
        String kafkaProducerTopic = extractionNamespace.getKafkaTopic();
        kafkaConsumer = ensureKafkaConsumer(kafkaProperties);
        kafkaConsumer.subscribe(Collections.singletonList(kafkaProducerTopic));
        ConsumerRecords<String, byte[]> records =  kafkaConsumer.poll(10000);

        for(ConsumerRecord<String, byte[]> record : records) {
            final String key = record.key();
            final byte[] message = record.value();

            if (key == null || message == null) {
                LOG.error("Bad key/message from topic [%s]", kafkaProducerTopic);
                continue;
            }

            LOG.error("Single record: " + record);

            updateLocalCache(extractionNamespace, cache, key, message);

        }
        LOG.error("Record returned: " + records);
        return new Callable<String>() {
            @Override
            public String call() {
                return "I like turtles.";
            }
        };

    }

    public void updateLocalCache(final JDBCExtractionNamespace extractionNamespace, Map<String, List<String>> cache,
                            final String key, final byte[] value) {
        Parser<Message> parser = protobufSchemaFactory.getProtobufParser(extractionNamespace.getLookupName());
        Descriptors.Descriptor descriptor = protobufSchemaFactory.getProtobufDescriptor(extractionNamespace.getLookupName());
        Descriptors.FieldDescriptor field = descriptor.findFieldByName(extractionNamespace.getTsColumn());
        Message newMessage;
        try {newMessage = parser.parseFrom(value);} catch (Exception e) {newMessage = null;}
        LOG.error("Message " + newMessage);
        if(!checkNamespaceAgainstMessageUpdateTS(cache, extractionNamespace, newMessage, field)) {
            LOG.error("Not updating the cache as the message in Kafka is older than current.");
        } else {
            LOG.error("Updating cache with new values");
            String pkvalue = newMessage.getField(descriptor.findFieldByName(extractionNamespace.getPrimaryKeyColumn())).toString();
            List<String> allProtobufValues =
                    newMessage.getAllFields().values()
                            .stream()
                            .map(object -> Objects.toString(object, null))
                            .collect(Collectors.toList());
            cache.put(pkvalue, allProtobufValues);
        }
    }

    boolean checkNamespaceAgainstMessageUpdateTS(Map<String, List<String>> cache,
                                                 JDBCExtractionNamespace extractionNamespace,
                                                 Message newMessage,
                                                 Descriptors.FieldDescriptor field) {
        Timestamp messageLastUpdated = Timestamp.valueOf(newMessage.getField(field).toString());
        Timestamp namespaceLastUpdated = extractionNamespace.getPreviousLastUpdateTimestamp();
        if(Objects.nonNull(namespaceLastUpdated) && messageLastUpdated.before(namespaceLastUpdated))
            return false;
        else {
            extractionNamespace.setPreviousLastUpdateTimestamp(namespaceLastUpdated);
            return true;
        }
    }


    private Callable<String> nonCacheEnabledCall(long lastCheck) {
        return () -> String.valueOf(lastCheck);
    }

    synchronized KafkaProducer<String, byte[]> ensureKafkaProducer(Properties kafkaProperties) {
        if(kafkaProducer == null) {
            kafkaProducer = new KafkaProducer<>(kafkaProperties);
        }
        return kafkaProducer;
    }

    synchronized KafkaConsumer<String, byte[]> ensureKafkaConsumer(Properties kafkaProperties) {
        if(kafkaConsumer == null) {
            kafkaConsumer = new KafkaConsumer<>(kafkaProperties);
        }
        return kafkaConsumer;
    }

    public void stop() {
        if(kafkaProducer != null) {
            kafkaProducer.flush();
            kafkaProducer.close();
        }
    }
}
