package com.kavach.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface KavachDao {

    // ---------------------------------------------------------------- policies

    @Query("SELECT * FROM app_policy")
    fun observePolicies(): Flow<List<AppPolicyEntity>>

    @Query("SELECT * FROM app_policy")
    suspend fun loadPolicies(): List<AppPolicyEntity>

    @Query("SELECT * FROM app_policy WHERE packageName = :packageName LIMIT 1")
    suspend fun policyFor(packageName: String): AppPolicyEntity?

    @Upsert
    suspend fun upsertPolicy(policy: AppPolicyEntity)

    @Upsert
    suspend fun upsertPolicies(policies: List<AppPolicyEntity>)

    @Query("DELETE FROM app_policy WHERE packageName = :packageName")
    suspend fun deletePolicy(packageName: String)

    // ------------------------------------------------------------ domain rules

    @Query("SELECT * FROM domain_rule")
    fun observeRules(): Flow<List<DomainRuleEntity>>

    @Query("SELECT * FROM domain_rule")
    suspend fun loadRules(): List<DomainRuleEntity>

    @Query("SELECT * FROM domain_rule WHERE packageName = :packageName ORDER BY domain ASC")
    fun observeRulesFor(packageName: String): Flow<List<DomainRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: DomainRuleEntity)

    @Query("DELETE FROM domain_rule WHERE packageName = :packageName AND domain = :domain")
    suspend fun deleteRule(packageName: String, domain: String)

    @Query("DELETE FROM domain_rule WHERE packageName = :packageName")
    suspend fun clearRulesFor(packageName: String)

    // -------------------------------------------------------------------- log

    @Insert
    suspend fun insertLog(entry: ConnectionLogEntity)

    @Insert
    suspend fun insertLogs(entries: List<ConnectionLogEntity>)

    @Query(
        """
        SELECT * FROM connection_log
        WHERE (:onlyBlocked = 0 OR blocked = 1)
          AND (:packageName IS NULL OR packageName = :packageName)
          AND (:query = '' OR domain LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    fun observeLog(
        onlyBlocked: Int,
        packageName: String?,
        query: String,
        limit: Int,
    ): Flow<List<ConnectionLogEntity>>

    @Query(
        """
        SELECT packageName,
               SUM(CASE WHEN blocked = 1 THEN 1 ELSE 0 END) AS blockedCount,
               SUM(CASE WHEN blocked = 0 THEN 1 ELSE 0 END) AS allowedCount
        FROM connection_log
        WHERE timestamp >= :since
        GROUP BY packageName
        """
    )
    fun observeTrafficCounts(since: Long): Flow<List<AppTrafficCounts>>

    @Query(
        """
        SELECT domain, COUNT(*) AS hits
        FROM connection_log
        WHERE blocked = 1 AND timestamp >= :since
        GROUP BY domain
        ORDER BY hits DESC
        LIMIT :limit
        """
    )
    fun observeTopBlocked(since: Long, limit: Int): Flow<List<DomainCount>>

    @Query("SELECT COUNT(*) FROM connection_log WHERE blocked = 1 AND timestamp >= :since")
    fun observeBlockedCount(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM connection_log WHERE timestamp >= :since")
    fun observeTotalCount(since: Long): Flow<Int>

    /** Distinct domains an app reached, used to bootstrap an allowlist after Learning mode. */
    @Query(
        """
        SELECT domain, COUNT(*) AS hits
        FROM connection_log
        WHERE packageName = :packageName
        GROUP BY domain
        ORDER BY hits DESC
        """
    )
    suspend fun domainsSeenFor(packageName: String): List<DomainCount>

    @Query("DELETE FROM connection_log")
    suspend fun clearLog()

    @Query("DELETE FROM connection_log WHERE timestamp < :cutoff")
    suspend fun trimLogOlderThan(cutoff: Long)

    /**
     * Hard cap on rows so a chatty app cannot fill the user's storage.
     * Deletes everything outside the newest [keep] rows.
     */
    @Query(
        """
        DELETE FROM connection_log
        WHERE id NOT IN (SELECT id FROM connection_log ORDER BY timestamp DESC LIMIT :keep)
        """
    )
    suspend fun trimLog(keep: Int)
}
