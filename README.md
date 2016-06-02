# Kappa

Exactly once stream processing of aggregates from [Kafka][kafka] using [Spark Streaming](http://spark.apache.org/streaming/)

## Wait, what?

Some of you may have heard about the [Lambda architecture][lambda].  The high level idea is that you want to perform an action (usually a query) of all of you data for all time.  The popular way of doing this is to use a combination of batch and stream processing.  Stream processing lets you get the freshest data, but often sacrificing accuracy.  Batch processing gives you high confidence in your accuracy but at the cost of delays in freshness. So you use batch processing for the older data, and stream processing for the newer data and combine the results to get the best of both worlds.

Then one day, Jay Kreps invented the [Kappa architecture][kappa].  The idea was to abandon batch processing entirely, and build out your stream processing architecture to handle all of your data needs.  This sounds cool, but what about the accuracy issue?  And what happens if the stream processing job fails and needs to be restarted?  One set of answers to these questions are really well explored by a pair of blog posts by [Tyler][stream-101] [Akidau][stream-102]. 

The hard part is reconciling event time, when the thing happened, with processing time, when your stream processing system sees it.  You have to assume that these will not be the same.  Events take time to get to your system, and events will come out of order.  If you're doing metrics by hour and an event shows up out of ourder and 3 hours late, you need to account for that.  You also need to account for failure.  Your processing job will fail, and it needs to be able to pick up where it left off.  

In this project, I'm building such a system.  Mostly I'm building it because after being exposed to Jay's ideas and reading Tyler's articles I thought it would be cool to build one.

## How does it work?

Lets start with the data.  Just like everyone doing examples with streaming data, I'm using twitter data.  But I need a few more things.  I need a buffer for my stream, and I need a way to keep track of where I am in my processing.  [Kafka][kafka] provides both of these things.In order to get my tweets into Kafka, I forked [kafka-twitter][kafka-twitter].  Just config and run, it's a great project for giving me a stream of tweets in a kafka stream.

For the stream processing, I'm using [Spark Streaming][spark-streaming].  There is really good integration with Kafka as a source, particularly around the direct api which gives fine-grained control over offsets (the position in our message buffer).  Spark Streaming uses a micro-batch approach to streaming.  This is measured in units of time, but remember this is processing time so we can't use it as event time.  Otherwise we could just say give me one minute windows and dump the count into my database.  Read [Tyler's post][stream-102] or the code for more details but basically for each window, we add a row to the database for each one minute time interval that has data in our window.  Since Kafka is a partitioned store, we do this for each partition.  It is important that we do this per partition because we need to keep track of our progress in the stream, and the process is per partition.  So we also save the start and end offset (position) in our database record.  

I've mentioned a database a few times now.  I'm using MySQL as an output database.  An efficient, relational database is ideal because at query time we need to aggregate the numbers from each processing window that gave us data for the relevant event time window.

## But does it scale?

There are definitely considerations you need to make in designing such a system.  Most significantly, you need to select your processing time window size and your event time window size.  If you want one-second granularity (event time) with up-to-the second results (processing time), and your events arrive between one second and 5 minutes late, then you're looking at a spread of 350 rows per second per partition.  With 50 partitions, you'll have a billion output records in under 16 hours which probably wont do very well over the long term.

If instead you want 10-minute granularity, with data that is fresh to the minute, and records can come in as much as 30 minutes late, now you're looking at 3 rows per minute per partition.  With 50 partitions, you'll have a billion output records in over 12 years--120 years with 10 minute freshness. That's pretty doable on MySql.  The astute reader will notice that I haven't said anything about how much data is comming in.  It doesn't matter how much data is coming in because all we're talking about here is the number of buckets.  The number in each bucket is just an aggregate.  That's what I'm really talking about when I say it will scale.

## How do I run it?

First, setup [Kafka][kafka] (you want version 0.8.2.2) and MySql (I'm using 5.7) on your local system.  Then grab some Twitter credentials and run [kafka-twitter][kafka-twitter] to start pumping tweets into Kafka.  The MYSQL file in this directory has MySql commands for generating a table with the correct schema.

You can modify the constants at the top of `src/main/java/com/matt_wise/kappa/KafkaExactlyOnce.java` if your MySql or Kafka are running on non-standard ports or if you're using a different table name.  Then use gradle to run it.

```bash
./gradlew run
```

## How do I see the results?

```sql
SELECT 
timestamp, sum(count) 
FROM aggregates.twitter 
GROUP BY timestamp 
ORDER BY timestamp;
```

timstamp           | sum(count)
-------------------|-----
2016-06-01 19:36:00|2778
2016-06-01 19:37:00|2649
2016-06-01 19:38:00|2616
2016-06-01 19:39:00|2696
2016-06-01 19:40:00|2785
2016-06-01 19:41:00|2765
2016-06-01 19:42:00|2684
2016-06-01 19:43:00|2661
2016-06-01 19:44:00|2574

[lambda]: http://lambda-architecture.net/
[kafka]: http://kafka.apache.org/
[spark-streaming]: http://spark.apache.org/streaming/
[kappa]: https://www.oreilly.com/ideas/questioning-the-lambda-architecture
[log]: https://engineering.linkedin.com/distributed-systems/log-what-every-software-engineer-should-know-about-real-time-datas-unifying
[stream-101]: https://www.oreilly.com/ideas/the-world-beyond-batch-streaming-101
[stream-102]: https://www.oreilly.com/ideas/the-world-beyond-batch-streaming-102
[kafka-twitter]: https://github.com/mawise/kafka-twitter