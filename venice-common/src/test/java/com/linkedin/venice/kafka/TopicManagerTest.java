package com.linkedin.venice.kafka;

import com.linkedin.venice.integration.utils.KafkaBrokerWrapper;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.TestUtils;
import com.linkedin.venice.meta.Version;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.linkedin.venice.utils.Utils;
import junit.framework.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Created by mwise on 6/9/16.
 */
public class TopicManagerTest {

  private KafkaBrokerWrapper kafka;
  private TopicManager manager;

  @BeforeClass
  public void setup() {
    kafka = ServiceFactory.getKafkaBroker();
    manager = new TopicManager(kafka.getZkAddress());
  }

  @AfterClass
  public void teardown() throws IOException {
    kafka.close();
    manager.close();
  }

  @Test
  public void testCreateTopic() throws Exception {
    String topicName = TestUtils.getUniqueString("testCreateTopic");
    int partitions = 1;
    int replicas = 1;
    manager.createTopic(topicName, partitions, replicas);
    Utils.waitForNonDeterministicCompetion(5, TimeUnit.SECONDS, () -> manager.listTopics().contains(topicName));
    Set<String> topicsInCluster = manager.listTopics();
    Assert.assertTrue(topicsInCluster.contains(topicName));
    manager.createTopic(topicName, partitions, replicas); /* should be noop */
    Assert.assertTrue(topicsInCluster.contains(topicName));
  }

  @Test
  public void testDeleteTopic() throws InterruptedException {

    // Create a topic
    String topicName = TestUtils.getUniqueString("testDeleteTopic");
    int partitions = 1;
    int replicas = 1;
    manager.createTopic(topicName, partitions, replicas);
    Utils.waitForNonDeterministicCompetion(10, TimeUnit.SECONDS, () -> manager.listTopics().contains(topicName));
    Assert.assertTrue(manager.listTopics().contains(topicName));

    // Delete that topic
    manager.deleteTopic(topicName);
    // Wait for it to go away (delete is async)
    Utils.waitForNonDeterministicCompetion(10, TimeUnit.SECONDS, () -> !manager.listTopics().contains(topicName));
    // Assert that it is gone
    Assert.assertFalse(manager.listTopics().contains(topicName));
  }

  @Test
  public void testDeleteOldTopics() throws InterruptedException {

    // Create 4 version topics, myStore_v1, myStore_v2, ...
    String storeName = TestUtils.getUniqueString("testDeleteOldTopics");
    int maxVersion = 4;
    for (int i=1;i<=maxVersion;i++){
      String topic = new Version(storeName, i).kafkaTopicName();
      manager.createTopic(topic, 1, 1);
      Utils.waitForNonDeterministicCompetion(10, TimeUnit.SECONDS, () -> manager.listTopics().contains(topic));
    }

    // Delete every version topic except for the newest 2 (by version number)
    manager.deleteOldTopicsForStore(storeName, 2);
    Utils.waitForNonDeterministicCompetion(10, TimeUnit.SECONDS, () -> {
      Set<String> topics = manager.listTopics();
      return !topics.contains(new Version(storeName, 1).kafkaTopicName()) && !topics.contains(new Version(storeName, 2).kafkaTopicName());
    });
    Set<String> topicsInCluster = manager.listTopics();
    Assert.assertFalse(topicsInCluster.contains(new Version(storeName, 1).kafkaTopicName()));
    Assert.assertFalse(topicsInCluster.contains(new Version(storeName, 2).kafkaTopicName()));
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 3).kafkaTopicName()));
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 4).kafkaTopicName()));

    // Do it again, nothing should change (because only those 2 topics are still around)
    manager.deleteOldTopicsForStore(storeName, 2);
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 3).kafkaTopicName()));
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 4).kafkaTopicName()));

  }

  @Test
  public void testDeleteOldTopicsByOldestToKeep() throws InterruptedException {

    // Create 4 version topics, myStore_v1, myStore_v2, ...
    String storeName = TestUtils.getUniqueString("testDeleteOldTopicsByOldestToKeep");
    int maxVersion = 4;
    for (int i=1;i<=maxVersion;i++){
      String topic = new Version(storeName, i).kafkaTopicName();
      manager.createTopic(topic, 1, 1);
      Utils.waitForNonDeterministicCompetion(10, TimeUnit.SECONDS, () -> manager.listTopics().contains(topic));
    }

    // Delete every topic with a version number older than 3
    manager.deleteTopicsForStoreOlderThanVersion(storeName, 3);
    Utils.waitForNonDeterministicCompetion(10, TimeUnit.SECONDS, () -> {
      Set<String> topics = manager.listTopics();
      return !topics.contains(new Version(storeName, 1).kafkaTopicName()) && !topics.contains(new Version(storeName, 2).kafkaTopicName());
    });

    Set<String> topicsInCluster = manager.listTopics();
    Assert.assertFalse(topicsInCluster.contains(new Version(storeName, 1).kafkaTopicName()));
    Assert.assertFalse(topicsInCluster.contains(new Version(storeName, 2).kafkaTopicName()));
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 3).kafkaTopicName()));
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 4).kafkaTopicName()));

    // Do it again, nothing should change
    manager.deleteTopicsForStoreOlderThanVersion(storeName, 3);
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 3).kafkaTopicName()));
    Assert.assertTrue(topicsInCluster.contains(new Version(storeName, 4).kafkaTopicName()));
  }

}