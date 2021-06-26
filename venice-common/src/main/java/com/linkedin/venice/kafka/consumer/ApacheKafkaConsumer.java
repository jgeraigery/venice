package com.linkedin.venice.kafka.consumer;

import com.linkedin.venice.annotation.NotThreadsafe;
import com.linkedin.venice.exceptions.UnsubscribedTopicPartitionException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.utils.VeniceProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.log4j.Logger;


/**
 * This class is not thread safe because of the internal {@link KafkaConsumer} being used.
 * backoff
 */
@NotThreadsafe
public class ApacheKafkaConsumer implements KafkaConsumerWrapper {
  private static final Logger logger = Logger.getLogger(ApacheKafkaConsumer.class);

  public static final String CONSUMER_POLL_RETRY_TIMES_CONFIG = "consumer.poll.retry.times";
  public static final String CONSUMER_POLL_RETRY_BACKOFF_MS_CONFIG = "consumer.poll.retry.backoff.ms";
  private static final boolean DEFAULT_PARTITIONS_OFFSETS_COLLECTION_ENABLE = false;
  private static final int CONSUMER_POLL_RETRY_TIMES_DEFAULT = 3;
  private static final int CONSUMER_POLL_RETRY_BACKOFF_MS_DEFAULT = 0;

  private final KafkaConsumer<KafkaKey, KafkaMessageEnvelope> kafkaConsumer;
  private final int consumerPollRetryTimes;
  private final int consumerPollRetryBackoffMs;
  private final Optional<TopicPartitionsOffsetsTracker> topicPartitionsOffsetsTracker;

  public ApacheKafkaConsumer(Properties props) {
    this(props, DEFAULT_PARTITIONS_OFFSETS_COLLECTION_ENABLE);
  }

  public ApacheKafkaConsumer(VeniceProperties props) {
    this(props.toProperties(), DEFAULT_PARTITIONS_OFFSETS_COLLECTION_ENABLE);
  }

  public ApacheKafkaConsumer(Properties props, boolean isKafkaConsumerOffsetCollectionEnabled) {
    this.kafkaConsumer = new KafkaConsumer<>(props);
    VeniceProperties veniceProperties = new VeniceProperties(props);
    this.consumerPollRetryTimes = veniceProperties.getInt(CONSUMER_POLL_RETRY_TIMES_CONFIG, CONSUMER_POLL_RETRY_TIMES_DEFAULT);
    this.consumerPollRetryBackoffMs = veniceProperties.getInt(CONSUMER_POLL_RETRY_BACKOFF_MS_CONFIG, CONSUMER_POLL_RETRY_BACKOFF_MS_DEFAULT);
    this.topicPartitionsOffsetsTracker = isKafkaConsumerOffsetCollectionEnabled ?
            Optional.of(new TopicPartitionsOffsetsTracker()) : Optional.empty();
    logger.info("Consumer poll retry times: " + this.consumerPollRetryTimes);
    logger.info("Consumer poll retry back off in ms: " + this.consumerPollRetryBackoffMs);
    logger.info("Consumer offset collection enabled: " + isKafkaConsumerOffsetCollectionEnabled);
  }

  private void seekNextOffset(TopicPartition topicPartition, long lastReadOffset) {
    // Kafka Consumer controls the default offset to start by the property
    // "auto.offset.reset" , it is set to "earliest" to start from the
    // beginning.

    // Venice would prefer to start from the beginning and using seekToBeginning
    // would have made it clearer. But that call always fail and can be used
    // only after the offsets are remembered for a partition in 0.9.0.2
    // TODO: Kafka has been upgraded to 0.11.*; we might be able to simply this function.
    if (lastReadOffset != OffsetRecord.LOWEST_OFFSET) {
      long nextReadOffset = lastReadOffset + 1;
      kafkaConsumer.seek(topicPartition, nextReadOffset);
    } else {
      // Considering the offset of the same consumer group could be persisted by some other consumer in Kafka.
      kafkaConsumer.seekToBeginning(Collections.singletonList(topicPartition));
    }
  }

  @Override
  public void subscribe(String topic, int partition, long lastReadOffset) {
    TopicPartition topicPartition = new TopicPartition(topic, partition);

    Set<TopicPartition> topicPartitionSet = kafkaConsumer.assignment();
    if (!topicPartitionSet.contains(topicPartition)) {
      List<TopicPartition> topicPartitionList = new ArrayList<>(topicPartitionSet);
      topicPartitionList.add(topicPartition);
      kafkaConsumer.assign(topicPartitionList);
      seekNextOffset(topicPartition, lastReadOffset);
      logger.info("Subscribed to Topic: " + topic + " Partition: " + partition + " Offset: " + lastReadOffset);
    } else {
      logger.warn("Already subscribed on Topic: " + topic + " Partition: " + partition
          + ", ignore the request of subscription.");
    }
  }

  @Override
  public void unSubscribe(String topic, int partition) {
    TopicPartition topicPartition = new TopicPartition(topic, partition);

    Set<TopicPartition> topicPartitionSet = kafkaConsumer.assignment();
    if (topicPartitionSet.contains(topicPartition)) {
      List<TopicPartition> topicPartitionList = new ArrayList<>(topicPartitionSet);
      if (topicPartitionList.remove(topicPartition)) {
        kafkaConsumer.assign(topicPartitionList);
      }
    }
    topicPartitionsOffsetsTracker.ifPresent(partitionsOffsetsTracker -> partitionsOffsetsTracker.removeTrackedOffsets(topicPartition));
  }

  @Override
  public void resetOffset(String topic, int partition) {
    if (!hasSubscription(topic, partition)) {
      throw new UnsubscribedTopicPartitionException(topic, partition);
    }
    TopicPartition topicPartition = new TopicPartition(topic, partition);
    kafkaConsumer.seekToBeginning(Collections.singletonList(topicPartition));
  }

  @Override
  public ConsumerRecords<KafkaKey, KafkaMessageEnvelope> poll(long timeoutMs) {
    // The timeout is not respected when hitting UNKNOWN_TOPIC_OR_PARTITION and when the
    // fetcher.retrieveOffsetsByTimes call inside kafkaConsumer times out,
    // TODO: we may want to wrap this call in our own thread to enforce the timeout...
    int attemptCount = 1;
    ConsumerRecords<KafkaKey, KafkaMessageEnvelope> records = ConsumerRecords.empty();
    while (attemptCount <= consumerPollRetryTimes) {
      try {
        records = kafkaConsumer.poll(Duration.ofMillis(timeoutMs));
        break;
      } catch (RetriableException e) {
        logger.warn(
            "Retriable exception thrown when attempting to consume records from kafka, attempt " + attemptCount + "/"
                + consumerPollRetryTimes, e);
        if (attemptCount == consumerPollRetryTimes) {
          throw e;
        }
        try {
          if (consumerPollRetryBackoffMs > 0) {
            Thread.sleep(consumerPollRetryBackoffMs);
          }
        } catch (InterruptedException ie) {
          // Here will still throw the actual exception thrown by internal consumer to make sure the stacktrace is meaningful.
          throw new VeniceException("Consumer poll retry back off sleep got interrupted", e);
        }
      } finally {
        attemptCount++;
      }
    }

    if (topicPartitionsOffsetsTracker.isPresent()) {
      topicPartitionsOffsetsTracker.get().updateEndOffsets(records, kafkaConsumer.metrics());
    }
    return records;
  }

  @Override
  public boolean hasSubscription() {
    return !kafkaConsumer.assignment().isEmpty();
  }

  @Override
  public boolean hasSubscription(String topic, int partition) {
    TopicPartition tp = new TopicPartition(topic, partition);
    return kafkaConsumer.assignment().contains(tp);
  }

  @Override
  public void pause(String topic, int partition) {
    TopicPartition tp = new TopicPartition(topic, partition);
    if (kafkaConsumer.assignment().contains(tp)) {
      kafkaConsumer.pause(Collections.singletonList(tp));
    }
  }

  /**
   * If the partitions were not previously paused, this method is a no-op.
   */
  @Override
  public void resume(String topic, int partition) {
    TopicPartition tp = new TopicPartition(topic, partition);
    kafkaConsumer.resume(Collections.singletonList(tp));
  }

  @Override
  public Set<TopicPartition> getAssignment() {
    return kafkaConsumer.assignment();
  }


  @Override
  public Set<TopicPartition> paused() {
    return kafkaConsumer.paused();
  }

  @Override
  public Map<TopicPartition, Long> beginningOffsets(List<TopicPartition> topicPartitions) {
    return kafkaConsumer.beginningOffsets(topicPartitions);
  }

  @Override
  public Map<TopicPartition, Long> endOffsets(List<TopicPartition> topicPartitions) {
    return kafkaConsumer.endOffsets(topicPartitions);
  }

  @Override
  public void assign(List<TopicPartition> topicPartitions) {
    kafkaConsumer.assign(topicPartitions);
  }

  @Override
  public void seek(TopicPartition topicPartition, long nextOffset) {
    kafkaConsumer.seek(topicPartition, nextOffset);
  }

  @Override
  public void close() {
    topicPartitionsOffsetsTracker.ifPresent(offsetsTracker -> offsetsTracker.clearAllOffsetState());
    if (kafkaConsumer != null) {
      kafkaConsumer.close();
    }
  }

  @Override
  public Optional<Long> getLatestOffset(String topic, int partition) {
    return topicPartitionsOffsetsTracker.isPresent() ?
    topicPartitionsOffsetsTracker.get().getEndOffset(topic, partition) : Optional.empty();
  }
}
