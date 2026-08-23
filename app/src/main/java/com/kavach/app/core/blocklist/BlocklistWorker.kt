package com.kavach.app.core.blocklist

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kavach.app.KavachApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Refreshes the compiled blocklists once a day on unmetered power-friendly conditions.
 *
 * Downloads are written to a temporary file first and only swapped into place
 * after the whole body has been read and sanity-checked. A truncated download
 * must never be able to replace a good list.
 */
class BlocklistWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? KavachApp ?: return@withContext Result.failure()

        // A run the user asked for explicitly ignores the automatic-update preference.
        val forced = inputData.getBoolean(KEY_FORCE, false)
        val settings = app.settingsRepository.current()
        if (!forced && !settings.blocklistAutoUpdate) return@withContext Result.success()

        val repository = app.blocklistRepository
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val jobs = listOf(
            BlocklistRepository.URL_TRACKERS to BlocklistRepository.FILE_TRACKERS,
            BlocklistRepository.URL_ADS to BlocklistRepository.FILE_ADS,
        )

        var updated = false
        var failed = false

        for ((url, fileName) in jobs) {
            when (download(client, url, repository.downloadedFile(fileName))) {
                DownloadOutcome.UPDATED -> updated = true
                DownloadOutcome.UNCHANGED -> Unit
                DownloadOutcome.FAILED -> failed = true
            }
        }

        if (updated) repository.reload()

        // Retry means WorkManager backs off and tries again; the old list stays live
        // in the meantime, so the user is never left unprotected by a failed update.
        if (failed && !updated) Result.retry() else Result.success()
    }

    private fun download(client: OkHttpClient, url: String, target: File): DownloadOutcome = try {
        val request = Request.Builder().url(url).header("User-Agent", "Kavach").build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                DownloadOutcome.FAILED
            } else {
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.outputStream().use { out -> body.byteStream().copyTo(out) }

                // A blocklist that suddenly collapses to almost nothing is far more
                // likely to be a bad deploy than a real change. Refuse it.
                val lines = countUsableLines(temp)
                if (lines < MIN_ACCEPTABLE_ENTRIES) {
                    temp.delete()
                    DownloadOutcome.FAILED
                } else if (target.exists() && target.length() == temp.length()) {
                    temp.delete()
                    DownloadOutcome.UNCHANGED
                } else {
                    if (temp.renameTo(target)) {
                        DownloadOutcome.UPDATED
                    } else {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                        DownloadOutcome.UPDATED
                    }
                }
            }
        }
    } catch (_: Throwable) {
        DownloadOutcome.FAILED
    }

    private fun countUsableLines(file: File): Int {
        var count = 0
        file.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null && count < MIN_ACCEPTABLE_ENTRIES) {
                if (DomainMatcher.parseLine(line) != null) count++
                line = reader.readLine()
            }
        }
        return count
    }

    private enum class DownloadOutcome { UPDATED, UNCHANGED, FAILED }

    companion object {
        /** Input-data key that forces a refresh even when auto-update is switched off. */
        const val KEY_FORCE = "force"

        private const val UNIQUE_NAME = "kavach-blocklist-update"
        private const val MIN_ACCEPTABLE_ENTRIES = 50

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
