/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.spark.sql;

import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.spark.RocketMQServerMock;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.rocketmq.RocketMQSourceProvider;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.Trigger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class RocketMQDataSourceTest {

    private static final Logger logger = LoggerFactory.getLogger(RocketMQDataSourceTest.class);

    private static final String CLASS_NAME = RocketMQSourceProvider.class.getCanonicalName();

    private static RocketMQServerMock mockServer;

    private static SparkSession spark;

    private static final String SPARK_CHECKPOINT_DIR = Files.createTempDir().getPath(); // ensure no checkpoint

    @BeforeClass
    public static void start() throws Exception {
        mockServer = new RocketMQServerMock(9878, 10003);
        mockServer.startupServer();

        Thread.sleep(2000);

        spark = SparkSession
                .builder()
                .config("spark.sql.shuffle.partitions", "4")
                .config("spark.sql.streaming.checkpointLocation", SPARK_CHECKPOINT_DIR)
                .master("local[4]")
                .appName("RocketMQSourceProviderTest")
                .getOrCreate();
    }

    @AfterClass
    public static void stop() {
        mockServer.shutdownServer();
        spark.cloneSession();

        try {
            FileUtils.deleteDirectory(new File(SPARK_CHECKPOINT_DIR));
        } catch (IOException ex) { /* ignore */ }
    }

    @Test
    public void testStructuredStreaming() throws Exception {
        String sourceTopic = "source-" + UUID.randomUUID().toString();
        String sinkTopic = "sink-" + UUID.randomUUID().toString();

        Dataset<Row> dfInput = spark
                .readStream()
                .format(CLASS_NAME)
                .option("nameServer", mockServer.getNameServerAddr())
                .option("topic", sourceTopic) // required
                .option("startingOffsets", "earliest")
                .option("subExpression", "*")
                .option("pullBatchSize", "32")
                .option("pullTimeout", "5000")
                .load();

        Dataset<Row> dfOutput = dfInput.select("body");

        StreamingQuery query = dfOutput.writeStream()
                .outputMode("append")
                .format(CLASS_NAME)
                .option("nameServer", mockServer.getNameServerAddr())
                .option("topic", sinkTopic)
                .trigger(Trigger.ProcessingTime("1 second"))
                .start();

        // Send 10 batches * 10 messages into MQ
        for (int batch = 0; batch < 10; batch++) {
            mockServer.prepareDataTo(sourceTopic, 10);
            Thread.sleep(500);
        }

        // Expect the streaming sql has completed all work
        Thread.sleep(5000);
        query.stop();

        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer("test_consumer");
        consumer.start();

        int messageCount = 0;
        Set<MessageQueue> mqs = consumer.fetchSubscribeMessageQueues(sinkTopic);
        for (MessageQueue mq : mqs) {
            PullResult pullResult = consumer.pull(mq, null, 0, 1000);
            if (pullResult.getPullStatus() == PullStatus.FOUND) {
                for (int i = 0; i < pullResult.getMsgFoundList().size(); i++) {
                    String messageBody = new String(pullResult.getMsgFoundList().get(i).getBody());
                    logger.info("Got message: " + messageBody);
                    assertEquals("\"Hello Rocket\"", messageBody.substring(0, 14));
                    messageCount++;
                }
            }
        }
        assertEquals(100, messageCount);

        consumer.shutdown();
    }

    @Test
    public void testBatchSource() throws Exception {
        String sourceTopic = "source-" + UUID.randomUUID().toString();
        mockServer.prepareDataTo(sourceTopic, 100);

        Dataset<Row> dfInput = spark
                .read()
                .format(CLASS_NAME)
                .option("nameServer", mockServer.getNameServerAddr())
                .option("topic", sourceTopic) // required
                .option("startingOffsets", "earliest")
                .option("endingOffsets", "latest")
                .option("pullBatchSize", "32")
                .option("pullTimeout", "5000")
                .load();

        dfInput.select("body").foreach(row -> {
            String messageBody = new String((byte[]) row.get(0));
            logger.info("Got message: " + messageBody);
            assertEquals("\"Hello Rocket\"", messageBody.substring(0, 14));
        });
        assertEquals(100, dfInput.select("body").count());
    }
}
