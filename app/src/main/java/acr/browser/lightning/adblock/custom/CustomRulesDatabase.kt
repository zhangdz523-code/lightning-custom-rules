package acr.browser.lightning.adblock.custom

import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.database.databaseDelegate
import acr.browser.lightning.extensions.safeUse
import acr.browser.lightning.extensions.useMap
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * A database that holds custom blocking rules, backed by SQLite.
 */
@SuppressLint("Range")
@Singleton
class CustomRulesDatabase @Inject constructor(
    application: Application,
    coroutineDispatchers: CoroutineDispatchers,
) : SQLiteOpenHelper(application, DATABASE_NAME, null, DATABASE_VERSION), CustomRulesRepository {

    private val databaseDispatcher = coroutineDispatchers.createDatabaseDispatcher()
    private val database: SQLiteDatabase by databaseDelegate()

    // In-memory cache of enabled rules for fast matching.
    private val enabledRulesCache = ConcurrentHashMap<String, RuleMatcher>()

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = "CREATE TABLE ${DatabaseUtils.sqlEscapeString(TABLE_RULES)}(" +
            "${DatabaseUtils.sqlEscapeString(KEY_PATTERN)} TEXT PRIMARY KEY," +
            "${DatabaseUtils.sqlEscapeString(KEY_ENABLED)} INTEGER NOT NULL DEFAULT 1" +
            ')'
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS ${DatabaseUtils.sqlEscapeString(TABLE_RULES)}")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        refreshCache()
    }

    override suspend fun addRule(rule: CustomRule): Boolean =
        withContext(NonCancellable + databaseDispatcher) {
            if (containsRule(rule.pattern)) return@withContext false
            val id = database.insert(TABLE_RULES, null, rule.toContentValues())
            if (id != -1L) {
                refreshCache()
                return@withContext true
            }
            false
        }

    override suspend fun removeRule(pattern: String): Boolean =
        withContext(NonCancellable + databaseDispatcher) {
            val deleted = database.delete(TABLE_RULES, "$KEY_PATTERN=?", arrayOf(pattern)) > 0
            if (deleted) refreshCache()
            deleted
        }

    override suspend fun setRuleEnabled(pattern: String, enabled: Boolean) =
        withContext(NonCancellable + databaseDispatcher) {
            database.execSQL(
                "UPDATE $TABLE_RULES SET $KEY_ENABLED=? WHERE $KEY_PATTERN=?",
                arrayOf(if (enabled) 1 else 0, pattern)
            )
            refreshCache()
        }

    override suspend fun getAllRules(): List<CustomRule> =
        withContext(NonCancellable + databaseDispatcher) {
            database.query(TABLE_RULES, null, null, null, null, null, "$KEY_PATTERN ASC").useMap {
                CustomRule(
                    pattern = it.getString(it.getColumnIndex(KEY_PATTERN)),
                    enabled = it.getInt(it.getColumnIndex(KEY_ENABLED)) == 1,
                )
            }
        }

    override suspend fun getEnabledRules(): List<CustomRule> =
        withContext(NonCancellable + databaseDispatcher) {
            database.query(TABLE_RULES, null, "$KEY_ENABLED=1", null, null, null, "$KEY_PATTERN ASC").useMap {
                CustomRule(
                    pattern = it.getString(it.getColumnIndex(KEY_PATTERN)),
                    enabled = true,
                )
            }
        }

    override fun matchesAnyRule(url: String): Boolean {
        if (enabledRulesCache.isEmpty()) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        val fullUrl = url.lowercase()
        return enabledRulesCache.values.any { it.matches(host, fullUrl) }
    }

    override suspend fun removeAllRules() =
        withContext(NonCancellable + databaseDispatcher) {
            database.delete(TABLE_RULES, null, null)
            refreshCache()
        }

    override suspend fun count(): Long =
        withContext(databaseDispatcher) {
            DatabaseUtils.queryNumEntries(database, TABLE_RULES)
        }

    private suspend fun containsRule(pattern: String): Boolean =
        withContext(NonCancellable + databaseDispatcher) {
            database.query(TABLE_RULES, arrayOf(KEY_PATTERN), "$KEY_PATTERN=?",
                arrayOf(pattern), null, null, null, "1"
            ).safeUse { return@withContext it.moveToFirst() }
            false
        }

    /**
     * Rebuild the in-memory cache of enabled rules from the database.
     */
    private suspend fun refreshCache() = withContext(NonCancellable + databaseDispatcher) {
        enabledRulesCache.clear()
        database.query(TABLE_RULES, null, "$KEY_ENABLED=1", null, null, null, null)
            .useMap {
                val pattern = it.getString(it.getColumnIndex(KEY_PATTERN))
                enabledRulesCache[pattern] = RuleMatcher(pattern)
            }
    }

    private fun CustomRule.toContentValues() = ContentValues(2).apply {
        put(KEY_PATTERN, pattern)
        put(KEY_ENABLED, if (enabled) 1 else 0)
    }

    /**
     * Compiles a rule pattern into a matcher that can test URLs efficiently.
     *
     * Supports:
     * - Exact domain: `ads.example.com`
     * - Wildcard subdomain: `*.tracker.com` (matches any subdomain)
     * - Wildcard anywhere: `*ads*` (substring match on host)
     * - URL pattern: `*pattern*` (substring match on full URL)
     */
    private class RuleMatcher(private val pattern: String) {
        private val isExactDomain = pattern.startsWith(".") == false &&
            !pattern.contains("*") && pattern.contains(".")
        private val isWildcardSubdomain = pattern.startsWith("*.")
        private val isWildcardPattern = pattern.contains("*")
        private val basePattern = pattern.removePrefix("*").removeSuffix("*").lowercase()
        private val compiledRegex: Regex? = if (isWildcardPattern) {
            val regexPattern = Regex.escape(pattern)
                .replace("\\*", ".*")
            try { Regex(regexPattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { null }
        } else {
            null
        }

        fun matches(host: String, fullUrl: String): Boolean {
            return when {
                isWildcardPattern -> {
                    // Substring match on host or full URL
                    host.contains(basePattern, ignoreCase = true) ||
                        fullUrl.contains(basePattern, ignoreCase = true)
                }
                isWildcardSubdomain -> {
                    // *.tracker.com matches tracker.com and any subdomain
                    host == basePattern || host.endsWith(".${basePattern}")
                }
                isExactDomain -> host == basePattern || host.endsWith(".${basePattern}")
                else -> host.contains(basePattern, ignoreCase = true)
            }
        }
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "customRules"
        private const val TABLE_RULES = "custom_rules"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_ENABLED = "enabled"
    }
}
