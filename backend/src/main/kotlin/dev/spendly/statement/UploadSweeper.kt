package dev.spendly.statement

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Safety net for AC-SD-3 when a scheduled deletion was lost (e.g. restart during the retention period):
 * deletes originals of statements that got past parsing and are older than the retention, abandoned
 * `.incoming-*.part` files, and files of statements that no longer exist.
 */
@Component
class UploadSweeper(private val storage: UploadStorage, private val statements: StatementRepository) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val name = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(csv|xls|xlsx|pdf)$")

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT15M")
    fun sweep() {
        try {
            Files.list(storage.root).use { files -> files.toList() }.forEach(::check)
        } catch (e: Exception) {
            log.warn("Upload sweep failed: {}", e.javaClass.simpleName)
        }
    }

    private fun check(file: Path) {
        val fileName = file.fileName.toString()
        val age = Duration.between(Files.getLastModifiedTime(file).toInstant(), Instant.now())
        if (fileName.startsWith(".incoming-") && fileName.endsWith(".part")) {
            if (age > Duration.ofHours(1)) storage.discard(file)
            return
        }
        val match = name.matchEntire(fileName) ?: return
        val statement = statements.findById(UUID.fromString(match.groupValues[1]))
        val parsed = statement != null && statement.status != StatementStatus.UPLOADED && statement.status != StatementStatus.FAILED
        if ((statement == null && age > Duration.ofHours(1)) || (parsed && age > storage.retention)) {
            storage.discard(file)
            log.info("Swept original {}", fileName)
        }
    }
}
