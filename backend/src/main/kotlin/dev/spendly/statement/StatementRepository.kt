package dev.spendly.statement

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

@Repository
class StatementRepository(private val jdbc: JdbcClient, private val jsonMapper: JsonMapper) {

    private val columns =
        "id, account_id, file_type, file_hash, status, last_completed_step, period_start, period_end, " +
            "error::text AS error, correlation_id, created_at, updated_at"

    fun insert(id: UUID, accountId: UUID, fileType: FileType, fileHash: String, correlationId: String) {
        jdbc.sql(
            """
            INSERT INTO statements (id, account_id, file_type, file_hash, status, last_completed_step, correlation_id)
            VALUES (:id, :accountId, :fileType, :fileHash, 'UPLOADED', 'UPLOAD', :correlationId)
            """.trimIndent(),
        ).param("id", id).param("accountId", accountId).param("fileType", fileType.name)
            .param("fileHash", fileHash).param("correlationId", correlationId).update()
    }

    fun findById(id: UUID): StatementRecord? =
        jdbc.sql("SELECT $columns FROM statements WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    /** Row lock for the duration of the caller's transaction. */
    fun lockById(id: UUID): StatementRecord? =
        jdbc.sql("SELECT $columns FROM statements WHERE id = :id FOR UPDATE").param("id", id).query(::map)
            .optional().orElse(null)

    fun findByAccountAndHash(accountId: UUID, fileHash: String): StatementRecord? =
        jdbc.sql("SELECT $columns FROM statements WHERE account_id = :accountId AND file_hash = :hash")
            .param("accountId", accountId).param("hash", fileHash).query(::map).optional().orElse(null)

    fun list(accountId: UUID?, status: StatementStatus?): List<StatementRecord> =
        jdbc.sql(
            """
            SELECT $columns FROM statements
            WHERE (CAST(:accountId AS uuid) IS NULL OR account_id = CAST(:accountId AS uuid))
              AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
            ORDER BY created_at DESC, id
            """.trimIndent(),
        ).param("accountId", accountId?.toString()).param("status", status?.name).query(::map).list()

    fun countByAccount(accountId: UUID): Long =
        jdbc.sql("SELECT count(*) FROM statements WHERE account_id = :accountId").param("accountId", accountId)
            .query(Long::class.java).single()

    /**
     * Guarded transition, enforced in SQL: only updates when the row is still in [expected].
     * Returns the number of rows changed (0 = someone else moved it first).
     */
    fun updateStatus(
        id: UUID,
        expected: StatementStatus,
        target: StatementStatus,
        step: ProcessingStep?,
        error: StatementError?,
        clearError: Boolean,
    ): Int = jdbc.sql(
        """
        UPDATE statements
        SET status = :target,
            last_completed_step = COALESCE(CAST(:step AS varchar), last_completed_step),
            error = CASE WHEN :clearError THEN NULL ELSE COALESCE(CAST(:error AS jsonb), error) END,
            updated_at = clock_timestamp()
        WHERE id = :id AND status = :expected
        """.trimIndent(),
    ).param("target", target.name).param("step", step?.name)
        .param("error", error?.let { jsonMapper.writeValueAsString(it) })
        .param("clearError", clearError).param("id", id).param("expected", expected.name).update()

    /** Records a completed step without a status change, guarded by the current status. */
    fun updateStep(id: UUID, expected: StatementStatus, step: ProcessingStep): Int =
        jdbc.sql("UPDATE statements SET last_completed_step = :step, updated_at = clock_timestamp() WHERE id = :id AND status = :expected")
            .param("step", step.name).param("id", id).param("expected", expected.name).update()

    fun updatePeriod(id: UUID, start: LocalDate, end: LocalDate) {
        jdbc.sql("UPDATE statements SET period_start = :start, period_end = :end WHERE id = :id")
            .param("start", start).param("end", end).param("id", id).update()
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = StatementRecord(
        id = rs.getObject("id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        fileType = FileType.valueOf(rs.getString("file_type")),
        fileHash = rs.getString("file_hash"),
        status = StatementStatus.valueOf(rs.getString("status")),
        lastCompletedStep = rs.getString("last_completed_step")?.let(ProcessingStep::valueOf),
        periodStart = rs.getObject("period_start", LocalDate::class.java),
        periodEnd = rs.getObject("period_end", LocalDate::class.java),
        error = rs.getString("error")?.let { jsonMapper.readValue(it, StatementError::class.java) },
        correlationId = rs.getString("correlation_id"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )
}
