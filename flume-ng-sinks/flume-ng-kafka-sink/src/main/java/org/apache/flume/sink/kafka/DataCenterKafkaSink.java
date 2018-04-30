/**
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 limitations under the License.
 */

package org.apache.flume.sink.kafka;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.conf.LogPrivacyUtil;
import org.apache.flume.instrumentation.kafka.KafkaSinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.apache.flume.source.avro.AvroFlumeEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.flume.sink.kafka.KafkaSinkConstants.*;


/**
 * A Flume Sink that can publish messages to Kafka.
 * This is a general implementation that can be used with any Flume agent and
 * a channel.
 * The message can be any event and the key is a string that we read from the
 * header
 * For use of partitioning, use an interceptor to generate a header with the
 * partition key
 * <p/>
 * Mandatory properties are:
 * brokerList -- can be a partial list, but at least 2 are recommended for HA
 * <p/>
 * <p/>
 * however, any property starting with "kafka." will be passed along to the
 * Kafka producer
 * Read the Kafka producer documentation to see which configurations can be used
 * <p/>
 * Optional properties
 * topic - there's a default, and also - this can be in the event header if
 * you need to support events with
 * different topics
 * batchSize - how many messages to process in one batch. Larger batches
 * improve throughput while adding latency.
 * requiredAcks -- 0 (unsafe), 1 (accepted by at least one broker, default),
 * -1 (accepted by all brokers)
 * useFlumeEventFormat - preserves event headers when serializing onto Kafka
 * <p/>
 * header properties (per event):
 * topic
 * key
 */
public class DataCenterKafkaSink extends AbstractSink implements Configurable {
  private static final Logger logger = LoggerFactory.getLogger(DataCenterKafkaSink.class);
  private ScheduledExecutorService scheduler;
  private KafkaConfigure kafkaConfigure;
  private final Properties kafkaProps = new Properties();
  private KafkaProducer<String, byte[]> producer;

  private boolean switchOn;
  private List<String> topics = new ArrayList<>();
  private int batchSize;
  private List<Future<RecordMetadata>> kafkaFutures;
  private KafkaSinkCounter counter;
  private boolean useAvroEventFormat;
  private String partitionHeader = null;
  private Integer staticPartitionId = null;
  private Optional<SpecificDatumWriter<AvroFlumeEvent>> writer =
          Optional.absent();
  private Optional<SpecificDatumReader<AvroFlumeEvent>> reader =
          Optional.absent();
  private Optional<ByteArrayOutputStream> tempOutStream = Optional
          .absent();

  //Fine to use null for initial value, Avro will create new ones if this
  // is null
  private BinaryEncoder encoder = null;

  Pattern datacenterPattern = Pattern.compile("^\\d+.\\d+\\|\\d+\\|([a-zA-Z0-9_-]*)\\|");

  public int getBatchSize() {
    return batchSize;
  }

  @Override
  public Status process() throws EventDeliveryException {
    //if sink is switch off, then just return
    if ( !this.switchOn ) {
      logger.warn("DataCenterKafkaSink is set off, just return.");
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
      }
      return Status.READY;
    }

    Status result = Status.READY;
    Channel channel = getChannel();
    Transaction transaction = null;
    Event event = null;
    String eventTopic = null;
    String eventKey = null;

    try {
      long processedEvents = 0;

      transaction = channel.getTransaction();
      transaction.begin();

      kafkaFutures.clear();
      long batchStartTime = System.nanoTime();
      for (; processedEvents < batchSize; processedEvents += 1) {
        event = channel.take();

        if (event == null) {
          // no events available in channel
          if (processedEvents == 0) {
            result = Status.BACKOFF;
            counter.incrementBatchEmptyCount();
          } else {
            counter.incrementBatchUnderflowCount();
          }
          break;
        }

        byte[] eventBody = event.getBody();
        Map<String, String> headers = event.getHeaders();

        eventTopic = headers.get(TOPIC_HEADER);
        if (eventTopic == null) {
          String line = new String(eventBody, "UTF-8");
          Matcher matcher = datacenterPattern.matcher(line);
          if (matcher.find()) {
            eventTopic = matcher.group(1);
          }
        }
        if (null != eventTopic && topics.contains(eventTopic)) {
          eventKey = headers.get(KEY_HEADER);
          if (logger.isTraceEnabled()) {
            if (LogPrivacyUtil.allowLogRawData()) {
              logger.trace("{Event} " + eventTopic + " : " + eventKey + " : "
                      + new String(eventBody, "UTF-8"));
            } else {
              logger.trace("{Event} " + eventTopic + " : " + eventKey);
            }
          }
          logger.debug("event #{}", processedEvents);

          // create a message and add to buffer
          long startTime = System.currentTimeMillis();

          Integer partitionId = null;
          try {
            ProducerRecord<String, byte[]> record;
            if (staticPartitionId != null) {
              partitionId = staticPartitionId;
            }
            //Allow a specified header to override a static ID
            if (partitionHeader != null) {
              String headerVal = event.getHeaders().get(partitionHeader);
              if (headerVal != null) {
                partitionId = Integer.parseInt(headerVal);
              }
            }
            if (partitionId != null) {
              record = new ProducerRecord<String, byte[]>(eventTopic, partitionId, eventKey,
                      serializeEvent(event, useAvroEventFormat));
            } else {
              record = new ProducerRecord<String, byte[]>(eventTopic, eventKey,
                      serializeEvent(event, useAvroEventFormat));
            }
            kafkaFutures.add(producer.send(record, new SinkCallback(startTime)));
          } catch (NumberFormatException ex) {
            throw new EventDeliveryException("Non integer partition id specified", ex);
          } catch (Exception ex) {
            // N.B. The producer.send() method throws all sorts of RuntimeExceptions
            // Catching Exception here to wrap them neatly in an EventDeliveryException
            // which is what our consumers will expect
            throw new EventDeliveryException("Could not send event", ex);
          }
        }
      }

      //Prevent linger.ms from holding the batch
      producer.flush();

      // publish batch and commit.
      if (processedEvents > 0) {
        for (Future<RecordMetadata> future : kafkaFutures) {
          future.get();
        }
        long endTime = System.nanoTime();
        counter.addToKafkaEventSendTimer((endTime - batchStartTime) / (1000 * 1000));
        counter.addToEventDrainSuccessCount(Long.valueOf(kafkaFutures.size()));
      }
      transaction.commit();

    } catch (Exception ex) {
      String errorMsg = "Failed to publish events";
      logger.error("Failed to publish events", ex);
      result = Status.BACKOFF;
      if (transaction != null) {
        try {
          kafkaFutures.clear();
          transaction.rollback();
          counter.incrementRollbackCount();
        } catch (Exception e) {
          logger.error("Transaction rollback failed", e);
          throw Throwables.propagate(e);
        }
      }
      throw new EventDeliveryException(errorMsg, ex);
    } finally {
      if (transaction != null) {
        transaction.close();
      }
    }

    return result;
  }

  @Override
  public synchronized void start() {
    // instantiate the producer
    producer = new KafkaProducer<String,byte[]>(kafkaProps);
    counter.start();

    this.scheduler = Executors.newSingleThreadScheduledExecutor();
    this.checkConfScheduler();
    super.start();
  }

  @Override
  public synchronized void stop() {
    producer.close();
    counter.stop();
    logger.info("Kafka Sink {} stopped. Metrics: {}", getName(), counter);
    super.stop();
  }


  /**
   * We configure the sink and generate properties for the Kafka Producer
   *
   * Kafka producer properties is generated as follows:
   * 1. We generate a properties object with some static defaults that
   * can be overridden by Sink configuration
   * 2. We add the configuration users added for Kafka (parameters starting
   * with .kafka. and must be valid Kafka Producer properties
   * 3. We add the sink's documented parameters which can override other
   * properties
   *
   * @param context
   */
  @Override
  public void configure(Context context) {
    this.kafkaConfigure = new KafkaConfigure(context.getString("kafka_conf_path", ""));
    KafkaConfigure.BaseConfigure baseConfigure = kafkaConfigure.checkUpdate();
    copyBaseSinkConf(baseConfigure);

    translateOldProps(context);

    if (logger.isDebugEnabled()) {
      logger.debug("Using batch size: {}", batchSize);
    }

    useAvroEventFormat = context.getBoolean(KafkaSinkConstants.AVRO_EVENT,
                                            KafkaSinkConstants.DEFAULT_AVRO_EVENT);

    partitionHeader = context.getString(KafkaSinkConstants.PARTITION_HEADER_NAME);
    staticPartitionId = context.getInteger(KafkaSinkConstants.STATIC_PARTITION_CONF);

    if (logger.isDebugEnabled()) {
      logger.debug(KafkaSinkConstants.AVRO_EVENT + " set to: {}", useAvroEventFormat);
    }

    kafkaFutures = new LinkedList<Future<RecordMetadata>>();

    String bootStrapServers = context.getString(BOOTSTRAP_SERVERS_CONFIG);
    if (bootStrapServers == null || bootStrapServers.isEmpty()) {
      throw new ConfigurationException("Bootstrap Servers must be specified");
    }

    setProducerProps(context, bootStrapServers);

    if (logger.isDebugEnabled() && LogPrivacyUtil.allowLogPrintConfig()) {
      logger.debug("Kafka producer properties: {}", kafkaProps);
    }

    if (counter == null) {
      counter = new KafkaSinkCounter(getName());
    }
  }

  private void translateOldProps(Context ctx) {
    //Broker List
    // If there is no value we need to check and set the old param and log a warning message
    if (!(ctx.containsKey(BOOTSTRAP_SERVERS_CONFIG))) {
      String brokerList = ctx.getString(BROKER_LIST_FLUME_KEY);
      if (brokerList == null || brokerList.isEmpty()) {
        throw new ConfigurationException("Bootstrap Servers must be specified");
      } else {
        ctx.put(BOOTSTRAP_SERVERS_CONFIG, brokerList);
        logger.warn("{} is deprecated. Please use the parameter {}",
                    BROKER_LIST_FLUME_KEY, BOOTSTRAP_SERVERS_CONFIG);
      }
    }

    // Acks
    if (!(ctx.containsKey(KAFKA_PRODUCER_PREFIX + ProducerConfig.ACKS_CONFIG))) {
      String requiredKey = ctx.getString(
              KafkaSinkConstants.REQUIRED_ACKS_FLUME_KEY);
      if (!(requiredKey == null) && !(requiredKey.isEmpty())) {
        ctx.put(KAFKA_PRODUCER_PREFIX + ProducerConfig.ACKS_CONFIG, requiredKey);
        logger.warn("{} is deprecated. Please use the parameter {}", REQUIRED_ACKS_FLUME_KEY,
                KAFKA_PRODUCER_PREFIX + ProducerConfig.ACKS_CONFIG);
      }
    }

    if (ctx.containsKey(KEY_SERIALIZER_KEY )) {
      logger.warn("{} is deprecated. Flume now uses the latest Kafka producer which implements " +
          "a different interface for serializers. Please use the parameter {}",
          KEY_SERIALIZER_KEY,KAFKA_PRODUCER_PREFIX + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
    }

    if (ctx.containsKey(MESSAGE_SERIALIZER_KEY)) {
      logger.warn("{} is deprecated. Flume now uses the latest Kafka producer which implements " +
                  "a different interface for serializers. Please use the parameter {}",
                  MESSAGE_SERIALIZER_KEY,
                  KAFKA_PRODUCER_PREFIX + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
    }
  }

  private void setProducerProps(Context context, String bootStrapServers) {
    kafkaProps.put(ProducerConfig.ACKS_CONFIG, DEFAULT_ACKS);
    //Defaults overridden based on config
    kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, DEFAULT_KEY_SERIALIZER);
    kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, DEFAULT_VALUE_SERIAIZER);
    kafkaProps.putAll(context.getSubProperties(KAFKA_PRODUCER_PREFIX));
    kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootStrapServers);
  }

  protected Properties getKafkaProps() {
    return kafkaProps;
  }

  private byte[] serializeEvent(Event event, boolean useAvroEventFormat) throws IOException {
    byte[] bytes;
    if (useAvroEventFormat) {
      if (!tempOutStream.isPresent()) {
        tempOutStream = Optional.of(new ByteArrayOutputStream());
      }
      if (!writer.isPresent()) {
        writer = Optional.of(new SpecificDatumWriter<AvroFlumeEvent>(AvroFlumeEvent.class));
      }
      tempOutStream.get().reset();
      AvroFlumeEvent e = new AvroFlumeEvent(toCharSeqMap(event.getHeaders()),
                                            ByteBuffer.wrap(event.getBody()));
      encoder = EncoderFactory.get().directBinaryEncoder(tempOutStream.get(), encoder);
      writer.get().write(e, encoder);
      encoder.flush();
      bytes = tempOutStream.get().toByteArray();
    } else {
      bytes = event.getBody();
    }
    return bytes;
  }

  private void checkConfScheduler() {

    this.scheduler.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        try {
          Calendar cal = Calendar.getInstance();
          logger.info("check kafka configure file. date = " + cal.getTime());

          KafkaConfigure.BaseConfigure configure = kafkaConfigure.checkUpdate();
          if (configure != null) {
            copyBaseSinkConf(configure);
          }
        } catch (Throwable e) {
          logger.error("checkConfScheduler: " + e.toString());
        }
      }
    }, 10, 30, TimeUnit.SECONDS);
  }

  private void copyBaseSinkConf(KafkaConfigure.BaseConfigure conf) {
    this.switchOn = conf.switchOn;
    this.batchSize = conf.batchSize;
    this.topics.clear();
    for (String topic : conf.topics) {
      if (StringUtils.isEmpty(topic))
        continue;
      this.topics.add(topic);
    }

  }

  private static Map<CharSequence, CharSequence> toCharSeqMap(Map<String, String> stringMap) {
    Map<CharSequence, CharSequence> charSeqMap = new HashMap<CharSequence, CharSequence>();
    for (Map.Entry<String, String> entry : stringMap.entrySet()) {
      charSeqMap.put(entry.getKey(), entry.getValue());
    }
    return charSeqMap;
  }

}
