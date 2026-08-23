package com.kavach.app.core.blocklist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/** The two compiled lists currently in memory, plus when they were built. */
data class Blocklists(
    val trackers: DomainMatcher = DomainMatcher.EMPTY,
    val ads: DomainMatcher = DomainMatcher.EMPTY,
    val updatedAt: Long = 0L,
    val source: Source = Source.NONE,
) {
    enum class Source { NONE, BUNDLED, DOWNLOADED }

    val totalDomains: Int get() = trackers.size + ads.size
}

/**
 * Owns the compiled tracker and advertising lists.
 *
 * Two tiers, in priority order:
 *  1. Files previously downloaded by [BlocklistWorker] into the app's private storage.
 *  2. The seed lists bundled in assets, so a fresh install is useful offline and
 *     before the first update ever runs.
 *
 * Compilation happens off the main thread and the result is published as an
 * immutable [Blocklists], which the policy snapshot then captures by reference.
 */
class BlocklistRepository(context: Context) {

    private val appContext = context.applicationContext

    private val state = MutableStateFlow(Blocklists())
    val lists: StateFlow<Blocklists> = state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val downloadedTrackers = downloadedFile(FILE_TRACKERS)
        val downloadedAds = downloadedFile(FILE_ADS)
        val hasDownloads = downloadedTrackers.exists() || downloadedAds.exists()

        val trackers = if (downloadedTrackers.exists()) {
            readDomains { downloadedTrackers.inputStream() }
        } else {
            readDomains { openAsset(FILE_TRACKERS) }
        }

        val ads = if (downloadedAds.exists()) {
            readDomains { downloadedAds.inputStream() }
        } else {
            readDomains { openAsset(FILE_ADS) }
        }

        state.value = Blocklists(
            trackers = DomainMatcher.of(trackers),
            ads = DomainMatcher.of(ads),
            updatedAt = maxOf(downloadedTrackers.lastModifiedOrZero(), downloadedAds.lastModifiedOrZero()),
            source = if (hasDownloads) Blocklists.Source.DOWNLOADED else Blocklists.Source.BUNDLED,
        )
    }

    /** Called by the worker once new content has been written to disk. */
    suspend fun reload() = load()

    fun downloadedFile(name: String): File = File(blocklistDir(), name)

    fun blocklistDir(): File = File(appContext.filesDir, DIR).apply { mkdirs() }

    suspend fun clearDownloads() = withContext(Dispatchers.IO) {
        blocklistDir().listFiles()?.forEach { it.delete() }
        load()
    }

    private fun openAsset(name: String): InputStream =
        appContext.assets.open("$DIR/$name")

    private fun File.lastModifiedOrZero(): Long = if (exists()) lastModified() else 0L

    /**
     * Reads a list file into a de-duplicated domain set.
     *
     * A missing or unreadable file yields an empty set rather than throwing: a
     * failed list update must degrade filtering, never prevent the tunnel starting.
     */
    private fun readDomains(open: () -> InputStream): Set<String> = try {
        open().use { stream ->
            val out = HashSet<String>(4096)
            BufferedReader(InputStreamReader(stream), 64 * 1024).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    DomainMatcher.parseLine(line)?.let(out::add)
                    line = reader.readLine()
                }
            }
            out
        }
    } catch (_: Throwable) {
        emptySet()
    }

    companion object {
        const val DIR = "blocklists"
        const val FILE_TRACKERS = "trackers.txt"
        const val FILE_ADS = "ads.txt"

        /**
         * Where updates come from.
         *
         * These point at the Kavach repository's own compiled artefacts rather than
         * at upstream lists directly. The nightly workflow does the parsing and
         * normalisation on CI, so the phone only ever downloads a clean,
         * already-validated file.
         */
        const val URL_TRACKERS =
            "https://raw.githubusercontent.com/akshat029/Kavach/main/blocklists/trackers.txt"
        const val URL_ADS =
            "https://raw.githubusercontent.com/akshat029/Kavach/main/blocklists/ads.txt"
    }
}
