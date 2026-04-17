version = "0.1.0"

project.extra["PluginName"]        = "Cow Killer"
project.extra["PluginDescription"] = "Kills cows in Lumbridge pen with style rotation (combat trainer)"

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
