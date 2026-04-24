plugins {
	`java-library`
}

subprojects {
	apply<JavaPlugin>()
	apply(plugin = "java-library")

	group = "net.vital.plugins"

	project.extra["PluginProvider"]     = "Vital"
	project.extra["ProjectSupportUrl"]  = "https://discord.gg/dx9y7uc3rf"
	project.extra["PluginLicense"]      = "BSD 2-Clause License"

	repositories {
		mavenLocal()
		mavenCentral()
		maven("https://repo.runelite.net")
		maven {
			name = "GitHubPackagesVitalAPIFork"
			url = uri("https://maven.pkg.github.com/xgoingleftyx/VitalAPI")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
		maven {
			name = "GitHubPackagesVitalAPI"
			url = uri("https://maven.pkg.github.com/Vitalflea/VitalAPI")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
		maven {
			name = "GitHubPackagesVitalShellFork"
			url = uri("https://maven.pkg.github.com/xgoingleftyx/VitalShell")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
		maven {
			name = "GitHubPackagesVitalShell"
			url = uri("https://maven.pkg.github.com/Vitalflea/VitalShell")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
	}

	dependencies {
		compileOnly(rootProject.libs.runelite.api)
		compileOnly(rootProject.libs.runelite.client)
		compileOnly(rootProject.libs.vital.api)
		compileOnly(rootProject.libs.guice)
		compileOnly(rootProject.libs.javax.annotation)
		compileOnly(rootProject.libs.lombok)
		compileOnly(rootProject.libs.pf4j)

		annotationProcessor(rootProject.libs.lombok)
		annotationProcessor(rootProject.libs.pf4j)
	}

	java {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	tasks {
		withType<JavaCompile> {
			options.encoding = "UTF-8"
		}

		withType<AbstractArchiveTask> {
			isPreserveFileTimestamps = false
			isReproducibleFileOrder  = true
			dirMode  = 493
			fileMode = 420
		}
	}

	afterEvaluate {
		tasks.withType<Jar> {
			doLast {
				val pluginsDir = file("${System.getProperty("user.home")}/.vitalclient/sideloaded-plugins")
				pluginsDir.mkdirs()
				val baseName = archiveBaseName.get()
				pluginsDir.listFiles()?.filter {
					it.name.startsWith("$baseName-") && it.name.endsWith(".jar") && it.name != archiveFileName.get()
				}?.forEach { old ->
					old.delete()
					println("Removed old jar: ${old.name}")
				}
				val dest = pluginsDir.resolve(archiveFileName.get())
				archiveFile.get().asFile.copyTo(dest, overwrite = true)
				println("Deployed ${archiveFileName.get()} to ${pluginsDir.absolutePath}")
			}
		}

		tasks.register("publish") {
			doLast {
				val buildFile = file("${project.name}.gradle.kts")
				if (!buildFile.exists()) error("No ${buildFile.name} in ${project.projectDir}")
				val content = buildFile.readText()
				val versionRegex = Regex("""version\s*=\s*"(\d+)\.(\d+)\.(\d+)"""")
				val match = versionRegex.find(content) ?: error("No version found in ${buildFile.path}")
				val major = match.groupValues[1].toInt()
				val minor = match.groupValues[2].toInt()
				val patch = match.groupValues[3].toInt()
				val newVersion = "$major.$minor.${patch + 1}"
				buildFile.writeText(content.replace(match.value, "version = \"$newVersion\""))

				exec {
					workingDir = rootProject.projectDir
					commandLine("${rootProject.projectDir}/gradlew.bat", ":${project.name}:jar")
				}

				val commitMsg = "${project.name} v$newVersion - ${project.property("commitMessage")}"
				exec {
					workingDir = rootProject.projectDir
					commandLine("git", "add", "-A", project.projectDir.absolutePath)
				}
				exec {
					workingDir = rootProject.projectDir
					commandLine("git", "commit", "-m", commitMsg)
				}
				exec {
					workingDir = rootProject.projectDir
					commandLine("git", "push", "origin", "main")
				}

				println("Published ${project.name} v$newVersion")
			}
		}
	}
}
