package io.confluent.examples.streams.kafka;

import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.kafka.common.protocol.SecurityProtocol;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import kafka.admin.AdminUtils;
import kafka.admin.RackAwareMode;
import kafka.server.KafkaConfig;
import kafka.server.KafkaConfig$;
import kafka.server.KafkaServer;
import kafka.utils.CoreUtils;
import kafka.utils.TestUtils;
import kafka.utils.SystemTime$;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;

/**
 * Runs an in-memory, "embedded" instance of a Kafka broker, which listens at `127.0.0.1:9092` by
 * default.
 *
 * Requires a running ZooKeeper instance to connect to.  By default, it expects a ZooKeeper instance
 * running at `127.0.0.1:2181`.  You can specify a different ZooKeeper instance by setting the
 * `zookeeper.connect` parameter in the broker's configuration.
 */
public class KafkaEmbedded {

  private static final Logger log = LoggerFactory.getLogger(KafkaEmbedded.class);

  private static final String DEFAULT_ZK_CONNECT = "127.0.0.1:2181";
  private static final int DEFAULT_ZK_SESSION_TIMEOUT_MS = 10 * 1000;
  private static final int DEFAULT_ZK_CONNECTION_TIMEOUT_MS = 8 * 1000;

  private final Properties effectiveConfig;
  private final File logDir;
  private final TemporaryFolder tmpFolder;
  private final KafkaServer kafka;

  /**
   * Creates and starts an embedded Kafka broker.
   *
   * @param config Broker configuration settings.  Used to modify, for example, on which port the
   *               broker should listen to.  Note that you cannot change the `log.dirs` setting
   *               currently.
   */
  public KafkaEmbedded(Properties config) throws IOException {
    tmpFolder = new TemporaryFolder();
    tmpFolder.create();
    logDir = tmpFolder.newFolder();
    effectiveConfig = effectiveConfigFrom(config);
    boolean loggingEnabled = true;

    KafkaConfig kafkaConfig = new KafkaConfig(effectiveConfig, loggingEnabled);
    log.debug("Starting embedded Kafka broker (with log.dirs={} and ZK ensemble at {}) ...",
        logDir, zookeeperConnect());
    kafka = TestUtils.createServer(kafkaConfig, SystemTime$.MODULE$);
    log.debug("Startup of embedded Kafka broker at {} completed (with ZK ensemble at {}) ...",
        brokerList(), zookeeperConnect());
  }

  private Properties effectiveConfigFrom(Properties initialConfig) throws IOException {
    Properties effectiveConfig = new Properties();
    effectiveConfig.put(KafkaConfig$.MODULE$.BrokerIdProp(), 0);
    effectiveConfig.put(KafkaConfig$.MODULE$.HostNameProp(), "127.0.0.1");
    effectiveConfig.put(KafkaConfig$.MODULE$.PortProp(), "9092");
    effectiveConfig.put(KafkaConfig$.MODULE$.NumPartitionsProp(), 1);
    effectiveConfig.put(KafkaConfig$.MODULE$.AutoCreateTopicsEnableProp(), true);
    effectiveConfig.put(KafkaConfig$.MODULE$.MessageMaxBytesProp(), 1000000);
    effectiveConfig.put(KafkaConfig$.MODULE$.ControlledShutdownEnableProp(), true);

    effectiveConfig.putAll(initialConfig);
    effectiveConfig.setProperty(KafkaConfig$.MODULE$.LogDirProp(), logDir.getAbsolutePath());
    return effectiveConfig;
  }

  /**
   * This broker's `metadata.broker.list` value.  Example: `127.0.0.1:9092`.
   *
   * You can use this to tell Kafka producers and consumers how to connect to this instance.
   */
  public String brokerList() {
    return String.join(":", kafka.config().hostName(), Integer.toString(kafka.boundPort(SecurityProtocol.PLAINTEXT)));
  }


  /**
   * The ZooKeeper connection string aka `zookeeper.connect`.
   */
  public String zookeeperConnect() {
    return effectiveConfig.getProperty("zookeeper.connect", DEFAULT_ZK_CONNECT);
  }

  /**
   * Stop the broker.
   */
  public void stop() {
    log.debug("Shutting down embedded Kafka broker at {} (with ZK ensemble at {}) ...",
        brokerList(), zookeeperConnect());
    kafka.shutdown();
    kafka.awaitShutdown();
    log.debug("Removing logs.dir at {} ...", logDir);
    List<String> logDirs = Collections.singletonList(logDir.getAbsolutePath());
    tmpFolder.delete();
    CoreUtils.delete(scala.collection.JavaConversions.asScalaBuffer(logDirs).seq());
    log.debug("Shutdown of embedded Kafka broker at {} completed (with ZK ensemble at {}) ...",
        brokerList(), zookeeperConnect());
  }

  /**
   * Create a Kafka topic with 1 partition and a replication factor of 1.
   *
   * @param topic The name of the topic.
   */
  public void createTopic(String topic) {
    createTopic(topic, 1, 1, new Properties());
  }

  /**
   * Create a Kafka topic with the given parameters.
   *
   * @param topic       The name of the topic.
   * @param partitions  The number of partitions for this topic.
   * @param replication The replication factor for (the partitions of) this topic.
   */
  public void createTopic(String topic, int partitions, int replication) {
    createTopic(topic, partitions, replication, new Properties());
  }

  /**
   * Create a Kafka topic with the given parameters.
   *
   * @param topic       The name of the topic.
   * @param partitions  The number of partitions for this topic.
   * @param replication The replication factor for (partitions of) this topic.
   * @param topicConfig Additional topic-level configuration settings.
   */
  public void createTopic(String topic,
                          int partitions,
                          int replication,
                          Properties topicConfig) {
    log.debug("Creating topic { name: {}, partitions: {}, replication: {}, config: {} }",
        topic, partitions, replication, topicConfig);
    // Note: You must initialize the ZkClient with ZKStringSerializer.  If you don't, then
    // createTopic() will only seem to work (it will return without error).  The topic will exist in
    // only ZooKeeper and will be returned when listing topics, but Kafka itself does not create the
    // topic.
    ZkClient zkClient = new ZkClient(
        zookeeperConnect(),
        DEFAULT_ZK_SESSION_TIMEOUT_MS,
        DEFAULT_ZK_CONNECTION_TIMEOUT_MS,
        ZKStringSerializer$.MODULE$);
    boolean isSecure = false;
    ZkUtils zkUtils = new ZkUtils(zkClient, new ZkConnection(zookeeperConnect()), isSecure);
    AdminUtils.createTopic(zkUtils, topic, partitions, replication, topicConfig, RackAwareMode.Enforced$.MODULE$);
    zkClient.close();
  }

}