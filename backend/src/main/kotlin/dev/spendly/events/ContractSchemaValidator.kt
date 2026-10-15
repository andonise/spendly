package dev.spendly.events

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import dev.spendly.config.SpendlyProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates Kafka envelopes against contracts/schemas (JSON Schema 2020-12). All schema files are
 * loaded into one registry keyed by their `$id`, so `$ref`s resolve locally; `spendly.local` is never fetched.
 */
@Component
class ContractSchemaValidator(properties: SpendlyProperties) {

    private val schemas: Map<String, Schema>

    init {
        schemas = load(properties.contractsPath().resolve("schemas"))
    }

    /** Throws [NonRetryableEventException] (SCHEMA_VALIDATION) when invalid. Errors carry paths only, never values. */
    fun validate(eventType: String, envelope: JsonNode) {
        val schema = schemas[eventType]
            ?: throw NonRetryableEventException(DlqReason.SCHEMA_VALIDATION, "Unknown eventType")
        val errors = schema.validate(envelope)
        if (errors.isNotEmpty()) {
            val summary = errors.take(5).joinToString("; ") { "${it.instanceLocation} (${it.keyword})" }
            throw NonRetryableEventException(DlqReason.SCHEMA_VALIDATION, "$eventType failed schema validation: $summary")
        }
    }

    companion object {
        const val ID_PREFIX = "https://spendly.local/contracts/schemas/"

        fun load(schemaDir: Path): Map<String, Schema> {
            val contents = Files.list(schemaDir).use { files ->
                files.filter { it.fileName.toString().endsWith(".schema.json") }
                    .toList()
                    .associate { ID_PREFIX + it.fileName.toString() to Files.readString(it, StandardCharsets.UTF_8) }
            }
            val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                builder.schemas(contents)
            }
            return EventTypes.SCHEMA_FILES.mapValues { (_, file) ->
                val id = ID_PREFIX + file
                check(contents.containsKey(id)) { "Missing schema $file in $schemaDir" }
                registry.getSchema(SchemaLocation.of(id))
            }
        }
    }
}
