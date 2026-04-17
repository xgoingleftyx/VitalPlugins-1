version = "0.1.0"

project.extra["PluginName"]        = "Test"
project.extra["PluginDescription"] = "Scratch plugin for testing VitalAPI methods"

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
