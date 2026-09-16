package acr.browser.lightning.adblock.source

import acr.browser.lightning.adblock.parser.HostsFileParser
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.database.adblock.Host
import acr.browser.lightning.log.Logger
import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * A [HostsDataSource] that merges multiple remote hosts lists, caches them on disk and
 * refreshes periodically, similar to how download managers bundle their rule sources.
 *
 * Behavior:
 * 1. If the on-disk cache is fresh (younger than [CACHE_MAX_AGE_MS]), it is used directly.
 * 2. Otherwise every source is downloaded in parallel; successes are merged and deduplicated
 *    into the cache.
 * 3. If all downloads fail, the stale cache is used when available.
 * 4. If no cache exists at all, the bundled assets list is used as a last resort.
 */
@Singleton
class AutoUpdateHostsDataSource @Inject constructor(
    private val application: Application,
    private val urlHostsDataSourceFactory: UrlHostsDataSource.Factory,
    private val hostsFileParserProvider: Provider<HostsFileParser>,
    private val assetsHostsDataSource: AssetsHostsDataSource,
    private val logger: Logger,
    private val coroutineDispatchers: CoroutineDispatchers,
) : HostsDataSource {

    /**
     * Parse a local cache file with the standard hosts file parser.
     */
    private fun parseFile(file: File): HostsResult {
        val hostsFileParser = hostsFileParserProvider.get()
        return try {
            InputStreamReader(FileInputStream(file)).use { reader ->
                val domains = hostsFileParser.parseInput(reader)
                logger.log(TAG, "Loaded ${domains.size} domains from cache")
                HostsResult.Success(domains)
            }
        } catch (exception: Exception) {
            HostsResult.Failure(exception)
        }
    }

    override suspend fun loadHosts(): HostsResult = withContext(coroutineDispatchers.io) {
        val cache = File(application.filesDir, CACHE_FILE)

        // 1. Fresh cache wins: zero network usage.
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS) {
            return@withContext parseFile(cache)
        }

        // 2. Refresh every source in parallel and merge the successes.
        val downloads = REMOTE_SOURCES.map { url ->
            async {
                try {
                    urlHostsDataSourceFactory.create(url).loadHosts()
                } catch (exception: Exception) {
                    logger.log(TAG, "Source failed: $url", exception)
                    HostsResult.Failure(exception)
                }
            }
        }.awaitAll()

        // Hosts to never block, even if a source lists them.
        val neverBlock = setOf(
            "localhost", "localhost.localdomain", "local",
            "broadcasthost", "ip6-localhost", "ip6-loopback"
        )

        val merged = LinkedHashSet<String>()
        var anySuccess = false
        for (result in downloads.filterIsInstance<HostsResult.Success>()) {
            anySuccess = true
            for (host in result.hosts) {
                if (host.name !in neverBlock) {
                    merged.add(host.name)
                }
            }
        }

        when {
            // 3. Write the merged cache and serve it.
            anySuccess -> {
                try {
                    cache.outputStream().bufferedWriter().use { writer ->
                        for (host in merged) {
                            writer.write(host)
                            writer.write('\n'.code)
                        }
                    }
                } catch (exception: Exception) {
                    logger.log(TAG, "Cache write failed", exception)
                }
                logger.log(TAG, "Loaded ${merged.size} unique domains from ${REMOTE_SOURCES.size} sources")
                HostsResult.Success(merged.map { Host(it) })
            }

            // 4. Network is unreachable: a stale cache is better than nothing.
            cache.exists() -> parseFile(cache)

            // 5. First run with no network: fall back to the bundled list.
            else -> assetsHostsDataSource.loadHosts()
        }
    }

    override suspend fun identifier(): String =
        "auto-update:${File(application.filesDir, CACHE_FILE).lastModified()}"

    companion object {
        private const val TAG = "AutoUpdateHostsDataSource"
        private const val CACHE_FILE = "auto_update_hosts.txt"

        /** Refresh at most once a day. */
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /**
         * The public hosts-format sources that 1DM subscribes to. Sources that fail to
         * download are simply skipped; only hosts-format lists are usable here because the
         * parser extracts plain domains.
         */
        private val REMOTE_SOURCES = listOf(
            "https://badmojr.github.io/1Hosts/Lite/hosts.txt",
            "https://hostsfile.org/Downloads/hosts.txt",
            "https://block.energized.pro/basic/formats/domains.txt"
        )
    }
}
