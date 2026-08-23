package com.kavach.app.vpn

import com.kavach.app.core.model.Verdict
import com.kavach.app.data.db.ConnectionLogEntity
import com.kavach.app.data.db.KavachDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Buffers Activity-log rows and writes them in batches.
 *
 * A busy device issues hundreds of DNS questions a minute. Inserting each one
 * individually would put a disk write on the resolution path and make every
 * lookup slower, so rows are queued in memory and flushed on a timer.
 *
 * The queue is bounded: if the flusher ever falls behind, the oldest entries are
 * dropped rather than growing without limit. Losing a log line is acceptable;
 * running the device out of memory is not.
 */
class ConnectionLogger(
    private val dao: KavachDao,
    private val scope: CoroutineScope,
) {

    private val queue = ConcurrentLinkedQueue<ConnectionLogEntity>()
    private val queued = AtomicInteger(0)

    @Volatile
    var enabled: Boolean = true

    private var job: Job? = null
    private var flushesSinceTrim = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    fun record(packageName: String?, uid: Int, question: DnsMessage.Question, verdict: Verdict) {
        if (!enabled) return
        if (queued.get() >= MAX_QUEUED) {
            queue.poll()?.let { queued.decrementAndGet() }
        }
        queue.add(
            ConnectionLogEntity(
                timestamp = System.currentTimeMillis(),
                packageName = packageName ?: UNKNOWN_PACKAGE,
                uid = uid,
                domain = question.name,
                queryType = DnsMessage.typeName(question.type),
                blocked = !verdict.allowed,
                reasonId = verdict.reason.name,
                detail = verdict.detail,
            )
        )
        queued.incrementAndGet()
    }

    suspend fun flush() {
        if (queue.isEmpty()) return
        val batch = ArrayList<ConnectionLogEntity>(minOf(queued.get(), MAX_BATCH))
        while (batch.size < MAX_BATCH) {
            val next = queue.poll() ?: break
            queued.decrementAndGet()
            batch.add(next)
        }
        if (batch.isEmpty()) return
        try {
            dao.insertLogs(batch)
            if (++flushesSinceTrim >= TRIM_EVERY_N_FLUSHES) {
                flushesSinceTrim = 0
                dao.trimLog(MAX_LOG_ROWS)
            }
        } catch (_: Throwable) {
            // The log is a convenience, never a correctness requirement. If the
            // database is unavailable we drop the batch and keep filtering.
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.launch(Dispatchers.IO) { flush() }
    }

    companion object {
        const val UNKNOWN_PACKAGE = "unknown"
        private const val FLUSH_INTERVAL_MS = 2_000L
        private const val MAX_QUEUED = 4_000
        private const val MAX_BATCH = 500
        private const val TRIM_EVERY_N_FLUSHES = 30
        const val MAX_LOG_ROWS = 20_000
    }
}
