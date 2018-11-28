// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.kafka.source;

import com.google.api.gax.core.CredentialsProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.pubsub.kafka.common.ConnectorUtils;
import com.google.pubsub.kafka.common.ConnectorCredentialsProvider;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.SubscriberGrpc.SubscriberFutureStub;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SourceConnector} that writes messages to a specific topic in <a
 * href="http://kafka.apache.org/">Apache Kafka</a>.
 */
public class CloudPubSubSourceConnector extends SourceConnector {

  private static final Logger log = LoggerFactory.getLogger(CloudPubSubSourceConnector.class);

  public static final String KAFKA_PARTITIONS_CONFIG = "kafka.partition.count";
  public static final String KAFKA_PARTITION_SCHEME_CONFIG = "kafka.partition.scheme";
  public static final String KAFKA_MESSAGE_KEY_CONFIG = "kafka.key.attribute";
  public static final String KAFKA_MESSAGE_TIMESTAMP_CONFIG = "kafka.timestamp.attribute";
  public static final String KAFKA_TOPIC_CONFIG = "kafka.topic";
  public static final String CPS_SUBSCRIPTION_CONFIG = "cps.subscription";
  public static final String CPS_MAX_BATCH_SIZE_CONFIG = "cps.maxBatchSize";
  public static final int DEFAULT_CPS_MAX_BATCH_SIZE = 100;
  public static final int DEFAULT_KAFKA_PARTITIONS = 1;
  public static final String DEFAULT_KAFKA_PARTITION_SCHEME = "round_robin";

  /** Defines the accepted values for the {@link #KAFKA_PARTITION_SCHEME_CONFIG}. */
  public enum PartitionScheme {
    ROUND_ROBIN("round_robin"),
    HASH_KEY("hash_key"),
    HASH_VALUE("hash_value"),
    KAFKA_PARTITIONER("kafka_partitioner");

    private String value;

    PartitionScheme(String value) {
      this.value = value;
    }

    public String toString() {
      return value;
    }

    public static PartitionScheme getEnum(String value) {
      if (value.equals("round_robin")) {
        return PartitionScheme.ROUND_ROBIN;
      } else if (value.equals("hash_key")) {
        return PartitionScheme.HASH_KEY;
      } else if (value.equals("hash_value")) {
        return PartitionScheme.HASH_VALUE;
      } else if (value.equals("kafka_partitioner")) {
        return PartitionScheme.KAFKA_PARTITIONER;
      } else {
        return null;
      }
    }

    /** Validator class for {@link CloudPubSubSourceConnector.PartitionScheme}. */
    public static class Validator implements ConfigDef.Validator {

      @Override
      public void ensureValid(String name, Object o) {
        String value = (String) o;
        if (!value.equals(CloudPubSubSourceConnector.PartitionScheme.ROUND_ROBIN.toString())
            && !value.equals(CloudPubSubSourceConnector.PartitionScheme.HASH_VALUE.toString())
            && !value.equals(CloudPubSubSourceConnector.PartitionScheme.HASH_KEY.toString())
            && !value.equals(CloudPubSubSourceConnector.PartitionScheme.KAFKA_PARTITIONER.toString())) {
          throw new ConfigException(
              "Valid values for "
                  + CloudPubSubSourceConnector.KAFKA_PARTITION_SCHEME_CONFIG
                  + " are " + Arrays.toString(PartitionScheme.values()));
        }
      }
    }
  }

  private Map<String, String> props;

  @Override
  public String version() {
    return AppInfoParser.getVersion();
  }

  @Override
  public void start(Map<String, String> props) {
    // Do a validation of configs here too so that we do not pass null objects to
    // verifySubscription().
    config().parse(props);
    String cpsProject = props.get(ConnectorUtils.CPS_PROJECT_CONFIG);
    String cpsSubscription = props.get(CPS_SUBSCRIPTION_CONFIG);
    String credentialsPath = props.get(ConnectorUtils.GCP_CREDENTIALS_FILE_PATH_CONFIG);
    ConnectorCredentialsProvider credentialsProvider = new ConnectorCredentialsProvider();
    if (credentialsPath != null) {
      try {
        credentialsProvider.loadFromFile(credentialsPath);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    verifySubscription(cpsProject, cpsSubscription, credentialsProvider);
    this.props = props;
    log.info("Started the CloudPubSubSourceConnector");
  }

  @Override
  public Class<? extends Task> taskClass() {
    return CloudPubSubSourceTask.class;
  }

  @Override
  public List<Map<String, String>> taskConfigs(int maxTasks) {
    // Each task will get the exact same configuration. Delegate config validation to the task.
    ArrayList<Map<String, String>> configs = new ArrayList<>();
    for (int i = 0; i < maxTasks; i++) {
      Map<String, String> config = new HashMap<>(props);
      configs.add(config);
    }
    return configs;
  }

  @Override
  public ConfigDef config() {
    return new ConfigDef()
        .define(
            KAFKA_TOPIC_CONFIG,
            Type.STRING,
            Importance.HIGH,
            "The topic in Kafka which will receive messages that were pulled from Cloud Pub/Sub.")
        .define(
            ConnectorUtils.CPS_PROJECT_CONFIG,
            Type.STRING,
            Importance.HIGH,
            "The project containing the topic from which to pull messages.")
        .define(
            CPS_SUBSCRIPTION_CONFIG,
            Type.STRING,
            Importance.HIGH,
            "The name of the subscription to Cloud Pub/Sub.")
        .define(
            CPS_MAX_BATCH_SIZE_CONFIG,
            Type.INT,
            DEFAULT_CPS_MAX_BATCH_SIZE,
            ConfigDef.Range.between(1, Integer.MAX_VALUE),
            Importance.MEDIUM,
            "The minimum number of messages to batch per pull request to Cloud Pub/Sub.")
        .define(
            KAFKA_MESSAGE_KEY_CONFIG,
            Type.STRING,
            null,
            Importance.MEDIUM,
            "The Cloud Pub/Sub message attribute to use as a key for messages published to Kafka.")
        .define(
            KAFKA_MESSAGE_TIMESTAMP_CONFIG,
            Type.STRING,
            null,
            Importance.MEDIUM,
            "The optional Cloud Pub/Sub message attribute to use as a timestamp for messages "
                + "published to Kafka. The timestamp is Long value.")
        .define(
            KAFKA_PARTITIONS_CONFIG,
            Type.INT,
            DEFAULT_KAFKA_PARTITIONS,
            ConfigDef.Range.between(1, Integer.MAX_VALUE),
            Importance.MEDIUM,
            "The number of Kafka partitions for the Kafka topic in which messages will be "
                + "published to.")
        .define(
            KAFKA_PARTITION_SCHEME_CONFIG,
            Type.STRING,
            DEFAULT_KAFKA_PARTITION_SCHEME,
            new PartitionScheme.Validator(),
            Importance.MEDIUM,
            "The scheme for assigning a message to a partition in Kafka.")
        .define(
            ConnectorUtils.GCP_CREDENTIALS_FILE_PATH_CONFIG,
            Type.STRING,
            null,
            Importance.HIGH,
            "The path to the GCP credentials file");
  }

  /**
   * Check whether the user provided Cloud Pub/Sub subscription name specified by {@link
   * #CPS_SUBSCRIPTION_CONFIG} exists or not.
   */
  @VisibleForTesting
  public void verifySubscription(String cpsProject, String cpsSubscription, CredentialsProvider credentialsProvider) {
    try {
      SubscriberFutureStub stub = SubscriberGrpc.newFutureStub(ConnectorUtils.getChannel(credentialsProvider));
      GetSubscriptionRequest request =
          GetSubscriptionRequest.newBuilder()
              .setSubscription(
                  String.format(
                      ConnectorUtils.CPS_SUBSCRIPTION_FORMAT, cpsProject, cpsSubscription))
              .build();
      stub.getSubscription(request).get();
    } catch (Exception e) {
      throw new ConnectException(
          "Error verifying the subscription " + cpsSubscription + " for project " + cpsProject, e);
    }
  }

  @Override
  public void stop() {}
}
