package dev.spendly.statement.domain

import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.crypto.toHex
import dev.spendly.shared.messaging.DlqReason
import dev.spendly.shared.messaging.NonRetryableEventException
import dev.spendly.shared.web.UnsupportedFileTypeException
import dev.spendly.statement.FileType
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component

/**
 * The temp upload folder (SPENDLY_UPLOAD_DIR). Files are stored as `<statementId>.<ext>`, never under the
 * original filename, and deleted after a successful parse (immediately, or after SPENDLY_UPLOAD_RETENTION).
 */
@Component
class UploadStorage(
    properties: SpendlyProperties,
    private val taskScheduler: TaskScheduler,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    final val root: Path = run {
        val configured = properties.upload.dir?.trim().orEmpty()
        check(configured.isNotEmpty()) { "SPENDLY_UPLOAD_DIR is not set (temp folder for uploaded statements, outside the repository)" }
        Path.of(configured).toAbsolutePath().normalize().also { Files.createDirectories(it) }
    }
    final val retention: Duration = properties.upload.retention

    data class Incoming(val path: Path, val sha256: String, val size: Long, val head: ByteArray)

    /** Streams the upload to a temp file inside the upload folder while hashing it. */
    fun receive(input: InputStream): Incoming {
        val tmp = root.resolve(".incoming-${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val head = java.io.ByteArrayOutputStream()
        var size = 0L
        input.use { inp ->
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    if (head.size() < HEAD_BYTES) head.write(buf, 0, minOf(n, HEAD_BYTES - head.size()))
                    size += n
                }
            }
        }
        return Incoming(tmp, digest.digest().toHex(), size, head.toByteArray())
    }

    fun moveInto(incoming: Incoming, fileName: String): Path {
        val target = root.resolve(fileName)
        Files.move(incoming.path, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun discard(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            log.warn("Could not delete temp file {}", path.fileName)
        }
    }

    /** Resolves an event's `tempFilePath` and rejects anything escaping the upload folder. */
    fun resolve(tempFilePath: String): Path {
        val resolved = root.resolve(tempFilePath).normalize()
        if (!resolved.startsWith(root) || resolved == root) {
            throw NonRetryableEventException(DlqReason.UNSAFE_TEMP_FILE_PATH, "tempFilePath escapes the upload folder")
        }
        return resolved
    }

    fun exists(fileName: String): Boolean = Files.isRegularFile(root.resolve(fileName))

    /** Deletes an original after a successful parse, honouring SPENDLY_UPLOAD_RETENTION (AC-SD-3). */
    fun deleteAfterSuccess(path: Path) {
        if (retention.isZero || retention.isNegative) {
            discard(path)
        } else {
            taskScheduler.schedule({ discard(path) }, Instant.now().plus(retention))
        }
    }

    companion object {
        const val HEAD_BYTES = 8192

        /** Detects the type from extension AND magic bytes; they must agree (else 415). */
        fun detectType(originalFilename: String?, head: ByteArray): FileType {
            val ext = originalFilename?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val byExtension = FileType.fromExtension(ext)
                ?: throw UnsupportedFileTypeException("Only CSV, XLS, XLSX and PDF files are accepted")
            val ok = when (byExtension) {
                FileType.PDF -> head.startsWith(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)) // %PDF-
                FileType.XLSX -> head.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // PK zip
                FileType.XLS -> head.startsWith(
                    byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()),
                )
                FileType.CSV -> looksLikeText(head)
            }
            if (!ok) throw UnsupportedFileTypeException("File content does not match its .$ext extension")
            return byExtension
        }

        private fun ByteArray.startsWith(prefix: ByteArray) =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        private fun looksLikeText(head: ByteArray): Boolean {
            if (head.isEmpty()) return false
            if (head.any { it == 0.toByte() }) return false
            // Must be valid UTF-8; endOfInput=false tolerates a multi-byte char cut at the end of the sample.
            val decoder = Charsets.UTF_8.newDecoder()
            val out = java.nio.CharBuffer.allocate(head.size)
            val result = decoder.decode(java.nio.ByteBuffer.wrap(head), out, head.size < HEAD_BYTES)
            return !result.isError
        }
    }
}
