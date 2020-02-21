package com.inovatrend.kafka.summit.service.sample01

import com.inovatrend.kafka.summit.service.ConsumerApp
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ForkJoinConsumerApp (consumerGroup: String, private val topic: String, var recordProcessingDurationMs: Int) : ConsumerApp {

    private val consumer : KafkaConsumer<String, String>
    private val stopped = AtomicBoolean(false)
    private val executor = Executors.newWorkStealingPool(10)
    private val activeWorkers = mutableListOf<ForkJoinRecordProcessingTask>()
    private var lastPollRecordsCount = 0
    private val pollHistory = mutableListOf<LocalDateTime>()
    private val log = LoggerFactory.getLogger(ForkJoinConsumerApp::class.java)

    init {
        val config = Properties()
        config[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = "localhost:9092";
        config[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java;
        config[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java;
        config[ConsumerConfig.GROUP_ID_CONFIG] = consumerGroup
        consumer = KafkaConsumer(config)
    }

    override fun startConsuming() {
        thread {
            try {
                consumer.subscribe(Collections.singleton(topic))
                while (!stopped.get()) {
                    updatePollMetrics()
                    val records = consumer.poll(Duration.of(1000, ChronoUnit.MILLIS))
                    this.lastPollRecordsCount = records.count()
                    log.info("Fetched {} records", lastPollRecordsCount)
                    val tasks = records.partitions().map { partition ->
                        val partitionRecords = records.records(partition)
                        val worker = ForkJoinRecordProcessingTask(partition, partitionRecords, recordProcessingDurationMs)
                        activeWorkers.add(worker)
                        worker
                    }
                    log.info("Invoking executors start, tasks : {}", tasks.size)
                    executor.invokeAll(tasks)
                    log.info("Invoking executors finish")
                    activeWorkers.clear()
                }
            } catch (we: WakeupException) {
                if (!stopped.get()) throw we
            } catch (e: Exception) {
                log.error("Failed to consume messages!", e)
            }
            finally {
                consumer.close()
            }
        }
    }

    private fun updatePollMetrics() {
        val now = LocalDateTime.now()
        pollHistory.add(now)
    }


    override fun stopConsuming(){
        stopped.set(true)
        consumer.wakeup()
    }


    override fun getActiveWorkers() = activeWorkers.toList()

    override fun getLastPollRecordsCount() = this.lastPollRecordsCount

    override fun updateRecordProcessingDuration(durationMs: Int) {
        log.info("Updating record processing duration: {} ms", durationMs)
        this.recordProcessingDurationMs = durationMs
        for (worker in activeWorkers.toList()) {
            worker.singleMsgProcessingDurationMs = durationMs
        }
    }

    override fun getPollHistory() =  pollHistory.toList()


}
