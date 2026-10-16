plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "dev.spendly"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.1"
extra["springModulithVersion"] = "2.1.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("org.springframework.modulith:spring-modulith-api")
	implementation("org.springframework.ai:spring-ai-starter-model-ollama")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
	implementation("org.springframework.ai:spring-ai-vector-store-advisor")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
	implementation("com.networknt:json-schema-validator:3.0.7")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-redpanda")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.springframework.modulith:spring-modulith-docs")
	testImplementation("io.mockk:mockk:1.14.11")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Tests never read the developer's real ../../.env (secrets, compose ports); they use Testcontainers
	// and synthetic secrets instead. Pointing the optional import at a missing file disables it.
	systemProperty("SPENDLY_ENV_FILE", layout.buildDirectory.file("no-such-env-file").get().asFile.absolutePath)
	systemProperty("spendly.test.contracts-dir", layout.projectDirectory.dir("../../contracts").asFile.absolutePath)
	systemProperty("spendly.test.samples-dir", layout.projectDirectory.dir("../../samples").asFile.absolutePath)
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

// The default test task never runs the categorization evaluation (it needs the real Ollama).
tasks.named<Test>("test") {
	useJUnitPlatform {
		excludeTags("eval")
	}
}

/**
 * Categorization accuracy against the real Ollama models on a fresh Testcontainers pgvector DB (system seed only,
 * no user learning). Usage: .\gradlew.bat evalCategorization [-Pgolden=<csv>] [-PnoSeed=true] [-PllmOff=true]
 * Writes build/reports/categorization-eval.json.
 */
tasks.register<Test>("evalCategorization") {
	description = "Evaluates categorization on a labelled CSV (description,category) with the real LLM"
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	useJUnitPlatform {
		includeTags("eval")
	}
	val golden = (project.findProperty("golden") as String?) ?: layout.projectDirectory.file("../../eval/golden/categorization-dev.csv").asFile.absolutePath
	systemProperty("spendly.eval.golden", file(golden).absolutePath)
	systemProperty("spendly.eval.no-seed", (project.findProperty("noSeed") as String?) ?: "false")
	systemProperty("spendly.eval.llm-off", (project.findProperty("llmOff") as String?) ?: "false")
	systemProperty("spendly.eval.report", layout.buildDirectory.file("reports/categorization-eval.json").get().asFile.absolutePath)
	(project.findProperty("ollamaBaseUrl") as String?)?.let { systemProperty("spendly.eval.ollama-base-url", it) }
	// Threshold overrides for tuning, e.g. -Pspendly.categorization.confidence-threshold=0.8
	project.properties.filterKeys { it.startsWith("spendly.") }.forEach { (k, v) -> systemProperty(k, v.toString()) }
	outputs.upToDateWhen { false }
	testLogging {
		events("passed", "failed", "standard_out")
		showStandardStreams = true
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}
