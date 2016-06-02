package com.matt_wise.kappa;

import com.fasterxml.jackson.databind.ObjectMapper;
import kafka.common.TopicAndPartition;
import kafka.message.MessageAndMetadata;
import kafka.serializer.StringDecoder;
import org.apache.spark.SparkConf;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.HasOffsetRanges;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.apache.spark.streaming.kafka.OffsetRange;
import scala.Tuple2;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by matt on 5/28/16.
 */
public class KafkaExactlyOnce {

    private static ObjectMapper mapper = new ObjectMapper();

    //TODO make these parameters
    private static String DB_CONNECTION = "mysql://localhost:3306/";
    private static String DB_NAME = "aggregates";
    private static String TOPIC = "twitter";
    private static String KAFKA = "localhost:9092";

    public static void main(String args[]) throws SQLException {

        // Hold a reference to the current offset ranges, so it can be used downstream
        final AtomicReference<OffsetRange[]> offsetRanges = new AtomicReference<>();

        Connection sqlConn = DriverManager.getConnection(
                "jdbc:" + DB_CONNECTION + DB_NAME,
                "root",
                "rootpass");
        sqlConn.setAutoCommit(false); /* all updates for offsetrange and partition form a transaction */

        String offsetQuery = "select partition_id, max(end_offset) from twitter group by partition_id";
        Statement queryStatement = sqlConn.createStatement();
        ResultSet offsetResults = queryStatement.executeQuery(offsetQuery);
        Map<TopicAndPartition, Long> fromOffsets = new HashMap<>();
        while (offsetResults.next()){
            int partition = offsetResults.getInt(1);
            long offset = offsetResults.getLong(2);
            fromOffsets.put(new TopicAndPartition(TOPIC, partition), offset);
        }
        if (fromOffsets.size() < 1){ /* if the database is empty, ie we're running this for the first time */
            /*
         It would be better to ask Kafka for the number of partitions so we can still
         build this Map dynamically when there isn't any data in the database yet.
          */
            fromOffsets.put(new TopicAndPartition(TOPIC, 0), 0L);
        }

        SparkConf conf = new SparkConf().setMaster("local[*]").setAppName("TwitterWordCount");
        JavaStreamingContext jssc = new JavaStreamingContext(conf, Durations.seconds(60));


        Map<String, String> kafkaParams = new HashMap<>();
        kafkaParams.put("metadata.broker.list", KAFKA);
        kafkaParams.put("auto.offset.reset", "smallest");

        JavaInputDStream<PartitionAndLong> directKafkaStream = KafkaUtils.createDirectStream(
                jssc,
                String.class,
                String.class,
                StringDecoder.class,
                StringDecoder.class,
                PartitionAndLong.class,
                kafkaParams,
                fromOffsets,
                /*
                 * The lambda below is a message handler, the message handler gives us access
                 * to the partition the message comes from and lets us build up the object
                 * we want to work with.
                 */
                (MessageAndMetadata<String, String> mmd) -> { //Message and Metadata
                    String jsonValue = mmd.message();
                    Map<String, Object> tweet = mapper.readValue(jsonValue, Map.class);
                    long unixTimestamp = Long.parseLong((String) tweet.getOrDefault("timestamp_ms", "0"));
                    long epochMinute = Util.minuteEpocFromUnix(unixTimestamp);
                    return new PartitionAndLong(mmd.partition(), epochMinute);
                }
        );


        // Just to pull out the offsets
        JavaDStream<PartitionAndLong> messages = directKafkaStream.transform(
                rdd -> {
                    OffsetRange[] offsets = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
                    offsetRanges.set(offsets);
                    return rdd;
                });


        JavaPairDStream<PartitionAndLong, Integer> timeCounts = messages
                .mapToPair(s -> new Tuple2<>(s, 1))
                .reduceByKey((a,b) -> a+b);

        timeCounts.foreachRDD(rdd -> {
            OffsetRange[] localOffsets = offsetRanges.get();
            String insertStatement = "INSERT INTO twitter (partition_id, start_offset, end_offset, timestamp, count)"
                    + " VALUES (?,?,?,?,?);";

            for (Tuple2<PartitionAndLong, Integer> localRecord : rdd.collect()){
                // This happens locally on the driver
                PartitionAndLong partitionAndLong = localRecord._1();
                Integer count = localRecord._2();

                // Print to Std out
                OffsetRange offsets = localOffsets[partitionAndLong.getPartition()];
                StringBuilder sb = new StringBuilder();
                sb.append(offsets.fromOffset() + "-" + offsets.untilOffset());
                sb.append(" ");
                sb.append(localRecord._1.toString());
                sb.append(" ");
                sb.append(count);
                System.out.println(sb.toString());

                // Write to DB
                PreparedStatement stmt = sqlConn.prepareStatement(insertStatement);
                stmt.setInt(1, partitionAndLong.getPartition());
                stmt.setLong(2, offsets.fromOffset());
                stmt.setLong(3, offsets.untilOffset());
                stmt.setTimestamp(4, new Timestamp(partitionAndLong.getvalue()));
                stmt.setInt(5, count);
                stmt.execute();
            }
            sqlConn.commit(); /* commit the transaction */
        });

        jssc.start();              // Start the computation
        jssc.awaitTermination();   // Wait for the computation to terminate

    }


}
