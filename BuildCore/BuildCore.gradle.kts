// BuildCore/BuildCore.gradle.kts
plugins {
	kotlin("jvm") version "2.1.0"
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
	jar {
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
}
