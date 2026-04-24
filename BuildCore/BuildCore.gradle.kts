// BuildCore/BuildCore.gradle.kts
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	kotlin("jvm") version "2.1.0"
	id("com.github.johnrengelman.shadow") version "8.1.1"
}

version = "0.1.0"

project.extra["PluginName"]        = "BuildCore"
project.extra["PluginDescription"] = "All-inclusive OSRS account builder foundation"

dependencies {
	// Kotlin runtime — bundled into shipped JAR since VitalShell classpath is unknown
	implementation(rootProject.libs.kotlin.stdlib)
	implementation(rootProject.libs.kotlin.reflect)
	implementation(rootProject.libs.coroutines.core)
	implementation(rootProject.libs.coroutines.swing)

	// JSON serialisation for JSONL event logging (Plan 3)
	implementation(rootProject.libs.jackson.module.kotlin)
	implementation(rootProject.libs.jackson.databind)
	implementation(rootProject.libs.jackson.datatype.jsr310)

	// VitalAPI — compileOnly because VitalShell's plugin classpath provides it at runtime
	compileOnly(rootProject.libs.vital.api)

	// GUI
	implementation(rootProject.libs.flatlaf)
	implementation(rootProject.libs.flatlaf.intellij)
	implementation(rootProject.libs.flatlaf.extras)

	// Test
	testImplementation(platform(rootProject.libs.junit.bom))
	testImplementation(rootProject.libs.junit.jupiter)
	testImplementation(rootProject.libs.mockk)
	testImplementation(rootProject.libs.konsist)
	testImplementation(rootProject.libs.coroutines.test)
}

kotlin {
	jvmToolchain(11)
}

tasks.test {
	useJUnitPlatform()
	testLogging {
		events("passed", "failed", "skipped")
		showStandardStreams = true
	}
}

tasks {
	// Kotlin + FlatLaf + coroutines must be bundled into the shipped JAR because
	// VitalShell's plugin classpath does not include them. We replace the default
	// `jar` artifact with the shaded one so the root `afterEvaluate` auto-deploy
	// hook ships the fat JAR, and later plan work doesn't break at runtime.
	jar {
		enabled = false
	}

	named<ShadowJar>("shadowJar") {
		archiveClassifier.set("")
		mergeServiceFiles()
		manifest {
			attributes(mapOf(
				"Plugin-Version"     to project.version,
				"Plugin-Id"          to project.name,
				"Plugin-Provider"    to project.extra["PluginProvider"],
				"Plugin-Description" to project.extra["PluginDescription"],
				"Plugin-License"     to project.extra["PluginLicense"]
			))
		}
	}

	build {
		dependsOn("shadowJar")
	}

	assemble {
		dependsOn("shadowJar")
	}
}
