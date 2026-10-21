package dev.spendly.report.persistence

import dev.spendly.report.domain.AccountScope
import dev.spendly.report.domain.BuiltReport
import dev.spendly.report.domain.DateRange
import dev.spendly.report.domain.Granularity
import dev.spendly.report.domain.Insight
import dev.spendly.report.domain.InsightType
import dev.spendly.report.domain.Period
import dev.spendly.report.domain.ReportRecord
import dev.spendly.report.domain.ReportStatus
import dev.spendly.report.domain.ReportSummary
import dev.spendly.report.domain.Severity
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/** `reports` and `insights`. Writes are guarded by `version` (optimistic locking, plan section 7). */
@Repository
class ReportRepository(private val jdbc: JdbcClient, private val json: JsonMapper) {

    fun find(period: Period, scope: AccountScope): ReportRecord? = jdbc.sql(
        "$SELECT WHERE granularity = :g AND period_start = :from AND period_end = :to AND account_scope = :scope",
    ).param("g", period.granularity.name).param("from", period.start).param("to", period.end).param("scope", scope.key)
        .query(::map).optional().orElse(null)

    fun findById(id: UUID): ReportRecord? = jdbc.sql("$SELECT WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    /** `SELECT … FOR UPDATE`: call inside a transaction. */
    fun lock(id: UUID): ReportRecord? = jdbc.sql("$SELECT WHERE id = :id FOR UPDATE").param("id", id).query(::map).optional().orElse(null)

    /** Inserts a PENDING report unless one with the same identity exists; returns the new ID or null. */
    fun insertPending(period: Period, scope: AccountScope, requested: Boolean): UUID? = jdbc.sql(
        """
        INSERT INTO reports (id, granularity, period_start, period_end, account_scope, status, version, requested_version)
        VALUES (:id, :g, :from, :to, :scope, 'PENDING', 1, :requested)
        ON CONFLICT (granularity, period_start, period_end, account_scope) DO NOTHING
        RETURNING id
        """.trimIndent(),
    ).param("id", UUID.randomUUID()).param("g", period.granularity.name).param("from", period.start).param("to", period.end)
        .param("scope", scope.key).param("requested", if (requested) 1 else 0)
        .query { rs, _ -> rs.getObject(1, UUID::class.java) }.optional().orElse(null)

    /**
     * Reports overlapping [range] whose scope includes one of [accountIds]: version + 1, READY → STALE (other statuses
     * keep theirs; a running build then fails its version check). Returns the updated rows.
     */
    fun markStale(accountIds: Collection<UUID>, range: DateRange): List<ReportRecord> = jdbc.sql(
        """
        UPDATE reports SET version = version + 1,
               status = CASE WHEN status = 'READY' THEN 'STALE' ELSE status END, updated_at = now()
        WHERE period_start <= :to AND period_end >= :from
          AND (account_scope = 'ALL' OR string_to_array(account_scope, ',') && string_to_array(:ids, ','))
        RETURNING $COLUMNS
        """.trimIndent(),
    ).param("from", range.from).param("to", range.to).param("ids", accountIds.joinToString(","))
        .query(::map).list()

    /** One report's inputs changed outside TransactionsChanged (its accounts changed): version + 1, READY → STALE. */
    fun markStale(id: UUID): ReportRecord? = jdbc.sql(
        """
        UPDATE reports SET version = version + 1,
               status = CASE WHEN status = 'READY' THEN 'STALE' ELSE status END, updated_at = now()
        WHERE id = :id RETURNING $COLUMNS
        """.trimIndent(),
    ).param("id", id).query(::map).optional().orElse(null)

    /** Records that a ReportRequested was written for [version]. */
    fun setRequested(id: UUID, version: Int) {
        jdbc.sql("UPDATE reports SET requested_version = GREATEST(requested_version, :v) WHERE id = :id AND version >= :v")
            .param("id", id).param("v", version).update()
    }

    /**
     * Saves a build only if the report is still at [version] (AC-TX-3); replaces that report's insights.
     * Returns false when a newer version exists (the result is discarded).
     */
    fun saveBuild(id: UUID, version: Int, built: BuiltReport): Boolean {
        val updated = jdbc.sql(
            """
            UPDATE reports SET status = 'READY', built_version = :v, coverage = :coverage, coverage_gaps = CAST(:gaps AS jsonb),
                   summary = CAST(:summary AS jsonb), accounts_fingerprint = :fp, built_at = now(), error = NULL, updated_at = now()
            WHERE id = :id AND version = :v
            """.trimIndent(),
        ).param("id", id).param("v", version).param("coverage", built.coverage.status.name)
            .param("gaps", json.writeValueAsString(built.summary.coverage.accounts.filter { it.gaps.isNotEmpty() }))
            .param("summary", json.writeValueAsString(built.summary)).param("fp", built.accountsFingerprint)
            .update()
        if (updated == 0) return false
        jdbc.sql("DELETE FROM insights WHERE report_id = :id").param("id", id).update()
        built.insights.forEachIndexed { i, insight ->
            jdbc.sql(
                """
                INSERT INTO insights (id, report_id, report_version, position, type, severity, message, payload)
                VALUES (:id, :report, :v, :pos, :type, :severity, :message, CAST(:payload AS jsonb))
                """.trimIndent(),
            ).param("id", UUID.randomUUID()).param("report", id).param("v", version).param("pos", i)
                .param("type", insight.type.name).param("severity", insight.severity.name).param("message", insight.message)
                .param("payload", json.writeValueAsString(insight.data)).update()
        }
        return true
    }

    /** A build gave up (DLQ): FAILED, only while still at [version]. The previous summary is kept. */
    fun markFailed(id: UUID, version: Int, error: String): Boolean = jdbc.sql(
        "UPDATE reports SET status = 'FAILED', error = :e, updated_at = now() WHERE id = :id AND version = :v AND status <> 'READY'",
    ).param("id", id).param("v", version).param("e", error.take(500)).update() == 1

    fun summary(id: UUID): ReportSummary? = jdbc.sql("SELECT summary FROM reports WHERE id = :id").param("id", id)
        .query { rs, _ -> rs.getString(1)?.let { json.readValue(it, ReportSummary::class.java) } }.optional().orElse(null)

    /** Insights of the version the summary was built from, in display order. */
    fun insights(id: UUID, builtVersion: Int): List<Insight> = jdbc.sql(
        "SELECT type, severity, message, payload FROM insights WHERE report_id = :id AND report_version = :v ORDER BY position",
    ).param("id", id).param("v", builtVersion).query { rs, _ ->
        Insight(
            InsightType.valueOf(rs.getString(1)), Severity.valueOf(rs.getString(2)), rs.getString(3),
            json.readValue(rs.getString(4), MAP_TYPE),
        )
    }.list()

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = ReportRecord(
        id = rs.getObject("id", UUID::class.java),
        period = Period(
            Granularity.valueOf(rs.getString("granularity")),
            DateRange(rs.getObject("period_start", LocalDate::class.java), rs.getObject("period_end", LocalDate::class.java)),
        ),
        scope = AccountScope.parse(rs.getString("account_scope")),
        status = ReportStatus.valueOf(rs.getString("status")),
        version = rs.getInt("version"),
        requestedVersion = rs.getInt("requested_version"),
        builtVersion = rs.getObject("built_version", Integer::class.java)?.toInt(),
        accountsFingerprint = rs.getString("accounts_fingerprint"),
        builtAt = rs.getObject("built_at", OffsetDateTime::class.java)?.toInstant(),
    )

    companion object {
        private const val COLUMNS =
            "id, granularity, period_start, period_end, account_scope, status, version, requested_version, built_version, accounts_fingerprint, built_at"
        private const val SELECT = "SELECT $COLUMNS FROM reports"
        private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
