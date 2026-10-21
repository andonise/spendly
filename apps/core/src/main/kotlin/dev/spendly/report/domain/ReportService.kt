package dev.spendly.report.domain

import dev.spendly.report.persistence.LedgerReader
import dev.spendly.report.persistence.ReportRepository
import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.ReportRequestReason
import dev.spendly.shared.messaging.ReportRequestedPayload
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.shared.outbox.ProcessedEventsRepository
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.FieldViolation
import dev.spendly.shared.web.NotFoundException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** A report as the API returns it (contracts `Report`). */
data class ReportView(
    val id: UUID,
    val granularity: Granularity,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val accountIds: List<UUID>?,
    val status: ReportStatus,
    val stale: Boolean,
    val version: Int,
    val builtAt: Instant?,
    val currency: String,
    val summary: ReportSummary?,
    val insights: List<Insight>,
)

/**
 * Report lifecycle (plan section 5): PENDING → READY → STALE → rebuilt. Reads are served from `reports`
 * (stale-while-revalidate); a missing or FAILED report is built during the request. Every build reads one
 * REPEATABLE READ snapshot (AC-TX-5) and saves only while `reports.version` is unchanged (AC-TX-3).
 */
@Service
class ReportService(
    private val reports: ReportRepository,
    private val reader: LedgerReader,
    private val builder: ReportBuilder,
    private val events: DomainEvents,
    private val processed: ProcessedEventsRepository,
    private val tx: TransactionTemplate,
    transactionManager: PlatformTransactionManager,
    properties: SpendlyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val currency = properties.baseCurrency.uppercase()
    private val maxDays = properties.reports.maxCustomDays

    /** Read-only REPEATABLE READ: totals, insights and coverage come from the same snapshot. */
    private val snapshot = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    fun get(granularity: Granularity, from: LocalDate, to: LocalDate?, accountIds: List<UUID>?): ReportView {
        val period = Period.of(granularity, from, to, maxDays)
        val scope = scopeOf(accountIds)
        val record = tx.execute { prepare(period, scope) }!!
        if (record.builtVersion == null || record.status == ReportStatus.FAILED) {
            buildAndSave(record.id, record.version)
        }
        return view(record.id)
    }

    fun getById(id: UUID): ReportView = view(id)

    fun coverage(from: LocalDate, to: LocalDate, accountIds: List<UUID>?): Coverage {
        if (to.isBefore(from)) throw bad("to", "must not be before from")
        val range = DateRange(from, to)
        if (range.days > maxDays) throw bad("to", "at most $maxDays days")
        val scope = scopeOf(accountIds)
        return inSnapshot { builder.coverage(range, scope) }
    }

    /** Runs [block] in one read-only REPEATABLE READ transaction (every report read and build uses it). */
    fun <T : Any> inSnapshot(block: () -> T): T = snapshot.execute { block() }!!

    /**
     * Builds [reportId] at [version] and saves it if the version is still current. Returns false when the report is
     * gone or a newer version exists (then the result is discarded, never saved).
     */
    fun buildAndSave(reportId: UUID, version: Int): Boolean {
        val record = reports.findById(reportId) ?: return false
        if (record.version != version) return false
        val built = inSnapshot { builder.build(record.period, record.scope) }
        val saved = tx.execute { reports.saveBuild(reportId, version, built) }!!
        if (saved) {
            log.info("Report {} v{} built: coverage {}, {} insight(s)", reportId, version, built.coverage.status, built.insights.size)
        } else {
            log.info("Report {} v{} build discarded: a newer version exists", reportId, version)
        }
        return saved
    }

    /** Report builder (group `spendly-backend-report-builder`): a redelivered or outdated request is harmless. */
    fun handle(env: EventEnvelope<ReportRequestedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        if (processed.exists(eventId, CONSUMER)) return
        buildAndSave(UUID.fromString(env.payload.reportId), env.payload.version)
        tx.executeWithoutResult { processed.tryInsert(eventId, CONSUMER) }
    }

    /** The build gave up after its retries (DLQ): FAILED while still at that version; the next view rebuilds it. */
    fun failed(env: EventEnvelope<ReportRequestedPayload>, reason: String) {
        val eventId = UUID.fromString(env.eventId)
        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, ConsumerNames.REPORT_DLQ)) return@executeWithoutResult
            val marked = reports.markFailed(UUID.fromString(env.payload.reportId), env.payload.version, "Build failed ($reason); see logs for eventId $eventId")
            log.warn("Report {} v{} build dead-lettered (reason={}, marked FAILED: {})", env.payload.reportId, env.payload.version, reason, marked)
        }
    }

    /** Find or create the report; a stale one gets its rebuild requested once per version. */
    private fun prepare(period: Period, scope: AccountScope): ReportRecord {
        val existing = reports.find(period, scope) ?: run {
            reports.insertPending(period, scope, requested = false)
            reports.find(period, scope)!!
        }
        var r = reports.lock(existing.id)!!
        if (r.status == ReportStatus.READY && r.accountsFingerprint != CoverageCalculator.fingerprint(reader.accounts(scope))) {
            r = reports.markStale(r.id)!! // an account was added, closed or re-dated since the build
        }
        if (r.status == ReportStatus.STALE && r.requestedVersion < r.version) {
            events.reportRequested(r.id, r.version, r.period.granularity.name, r.period.start, r.period.end, r.scope.key, ReportRequestReason.STALE_VIEWED, correlationId())
            reports.setRequested(r.id, r.version)
        }
        return r
    }

    private fun view(id: UUID): ReportView {
        val r = reports.findById(id) ?: throw NotFoundException("Report $id")
        val built = r.builtVersion
        return ReportView(
            id = r.id,
            granularity = r.period.granularity,
            periodStart = r.period.start,
            periodEnd = r.period.end,
            accountIds = r.scope.accountIds,
            status = r.status,
            stale = built != null && built < r.version,
            version = r.version,
            builtAt = r.builtAt,
            currency = currency,
            summary = if (built == null) null else reports.summary(id),
            insights = if (built == null) emptyList() else reports.insights(id, built),
        )
    }

    private fun scopeOf(accountIds: List<UUID>?): AccountScope {
        val scope = AccountScope.of(accountIds)
        val ids = scope.accountIds ?: return scope
        val known = reader.accounts(scope).map { it.id }.toSet()
        val unknown = ids.filterNot(known::contains)
        if (unknown.isNotEmpty()) throw bad("accountId", "unknown account ${unknown.first()}")
        return scope
    }

    private fun correlationId(): String = MDC.get("correlationId") ?: "report-${UUID.randomUUID()}"

    private fun bad(field: String, message: String) = BadRequestException("VALIDATION_ERROR", "Invalid $field: $message", listOf(FieldViolation(field, message)))

    companion object {
        const val CONSUMER = ConsumerNames.REPORT_BUILDER
    }
}
