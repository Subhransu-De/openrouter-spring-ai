import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension

plugins {
	base
	id("net.ltgt.errorprone") apply false
	id("org.springframework.boot") apply false
	id("org.graalvm.buildtools.native") apply false
}

val pomText = file("pom.xml").readText()
fun pomProperty(name: String): String =
	Regex("<${Regex.escape(name)}>([^<]+)</${Regex.escape(name)}>")
		.find(pomText)
		?.groupValues
		?.get(1)
		?: error("Missing <$name> in the authoritative Maven POM")

val nullawayEnabled = providers.gradleProperty("nullaway").isPresent
if (nullawayEnabled) {
	require(JavaVersion.current() >= JavaVersion.VERSION_25) { "NullAway requires build JDK 25 or newer; output remains Java 17" }
}

val javaVersion = pomProperty("java.version").toInt()
val revision = pomProperty("revision")
val springAiVersion = pomProperty("spring-ai.version")
val springBootVersion = pomProperty("spring-boot.version")
val jackson3Version = pomProperty("jackson3.version")
val junitJupiterVersion = pomProperty("junit-jupiter.version")
val archunitVersion = pomProperty("archunit.version")
val checkstyleVersion = pomProperty("checkstyle.version")
val pmdVersion = pomProperty("pmd.version")
val springJavaFormatVersion = pomProperty("spring-javaformat.version")
val jacocoVersion = pomProperty("jacoco-maven-plugin.version")
val coverageLineMinimum = pomProperty("coverage.line.minimum").toBigDecimal()
val coverageBranchMinimum = pomProperty("coverage.branch.minimum").toBigDecimal()
val libraryProjects =
	setOf("openrouter-spring-ai", "openrouter-spring-ai-autoconfigure", "openrouter-spring-ai-starter")

extra["archunitVersion"] = archunitVersion
extra["lombokVersion"] = pomProperty("lombok.version")
extra["twelveMonkeysVersion"] = pomProperty("twelvemonkeys.version")
extra["pitestVersion"] = pomProperty("pitest.version")
extra["pitestJunitVersion"] = pomProperty("pitest-junit5.version")
extra["mutationTargets"] = pomProperty("mutation.targets")
extra["mutationThreads"] = pomProperty("mutation.threads")

allprojects {
	group = "de.subhransu"
	version = revision
}

subprojects {
	apply(plugin = "java-library")
	apply(plugin = "checkstyle")
	apply(plugin = "jacoco")
	apply(plugin = "pmd")
	if (name in libraryProjects) {
		apply(plugin = "maven-publish")
	}

	if (name in setOf("openrouter-spring-ai", "openrouter-spring-ai-autoconfigure")) {
		val spotbugsEngine = configurations.create("spotbugsEngine") { isCanBeConsumed = false }
		val spotbugsVisibilityPlugin = configurations.create("spotbugsVisibilityPlugin") {
			isCanBeConsumed = false
			isTransitive = false
		}
		dependencies {
			add(spotbugsEngine.name, "com.github.spotbugs:spotbugs:${pomProperty("spotbugs.version")}")
			add(spotbugsVisibilityPlugin.name, "com.mebigfatguy.sb-contrib:sb-contrib:${pomProperty("sb-contrib.version")}")
		}
		val main = extensions.getByType<SourceSetContainer>()["main"]
		for (policy in listOf("correctness", "visibility")) {
			tasks.register<JavaExec>("spotbugs${policy.replaceFirstChar { it.uppercase() }}") {
				group = "verification"
				description = "Analyze production classes with the SpotBugs $policy policy"
				dependsOn(tasks.named("classes"), main.compileClasspath)
				classpath = spotbugsEngine
				mainClass.set("edu.umd.cs.findbugs.FindBugs2")
				maxHeapSize = "512m"
				isIgnoreExitValue = true
				val reportDir = layout.buildDirectory.dir("reports/spotbugs/$policy")
				val include = rootProject.file("config/spotbugs/$policy-include.xml")
				val exclude = rootProject.file("config/spotbugs/$policy-exclude.xml")
				doFirst {
					check(!main.output.classesDirs.asFileTree.matching {
						include("**/*.class")
						exclude("**/package-info.class", "**/module-info.class")
					}.isEmpty) { "Empty required SpotBugs $policy analysis scope" }
					val directory = reportDir.get().asFile.apply { mkdirs() }
					val auxiliary = directory.resolve("classpath.txt")
					auxiliary.writeText(main.compileClasspath.files.joinToString("\n") { it.absolutePath } + "\n")
					setArgs(listOf("-effort:max", "-low", "-exitcode", "-nested:false",
						"-include", include.absolutePath, "-exclude", exclude.absolutePath,
						"-auxclasspathFromFile", auxiliary.absolutePath,
						"-xml:withMessages=${directory.resolve("spotbugs.xml")}",
						"-html=${directory.resolve("spotbugs.html")}"))
					if (policy == "visibility") args("-pluginList", spotbugsVisibilityPlugin.asPath)
					args(main.output.classesDirs.files.map { it.absolutePath })
				}
				doLast {
					val exit = executionResult.get().exitValue
					// SpotBugs returns 1 for findings, 2 for missing classes, and 4 for analysis errors.
					check(exit == 0 || (policy == "visibility" && exit == 1)) {
						"SpotBugs $policy failed with exit code $exit; see ${reportDir.get().asFile}"
					}
				}
			}
		}
	}

	if (nullawayEnabled) {
		apply(plugin = "net.ltgt.errorprone")
		dependencies {
			"errorprone"("com.google.errorprone:error_prone_core:${pomProperty("error-prone.version")}")
			"errorprone"("com.uber.nullaway:nullaway:${pomProperty("nullaway.version")}")
		}
		tasks.withType<JavaCompile>().configureEach {
			// Analyze records and their callers together, including type-use annotations.
			options.isIncremental = false
			options.errorprone {
				enabled.set(name == "compileJava")
				disableAllChecks.set(true)
				disableWarningsInGeneratedCode.set(true)
				error("NullAway")
				option("NullAway:OnlyNullMarked", "true")
				option("NullAway:JSpecifyMode", "true")
				option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
				option("NullAway:CustomContractAnnotations", "org.springframework.lang.Contract")
			}
		}
	}

	tasks.withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.release.set(javaVersion)
		options.compilerArgs.add("-Xpkginfo:always")
	}

	dependencies {
		"api"(platform("tools.jackson:jackson-bom:$jackson3Version"))
		"api"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
		"api"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
		"annotationProcessor"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
		"testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
		"testImplementation"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
		"testImplementation"(platform("org.junit:junit-bom:$junitJupiterVersion"))
		"testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
		"checkstyle"("com.puppycrawl.tools:checkstyle:$checkstyleVersion")
		"checkstyle"("io.spring.javaformat:spring-javaformat-checkstyle:$springJavaFormatVersion")
	}

	configure<CheckstyleExtension> {
		toolVersion = checkstyleVersion
		configFile = rootProject.file("config/checkstyle/checkstyle.xml")
		isShowViolations = true
	}

	configure<PmdExtension> {
		toolVersion = pmdVersion
		isConsoleOutput = true
		isIgnoreFailures = false
		ruleSets = emptyList()
		ruleSetFiles = rootProject.files("config/pmd/pmd-common.xml", "config/pmd/pmd-main.xml", "config/pmd/pmd-test.xml")
	}

	configure<JacocoPluginExtension> {
		toolVersion = jacocoVersion
	}

	tasks.withType<Jar>().configureEach {
		isPreserveFileTimestamps = false
		isReproducibleFileOrder = true
		entryCompression = ZipEntryCompression.STORED
	}

	tasks.withType<Checkstyle>().configureEach {
		// Checkstyle 13.x and 14.x require Java 21; style is enforced by the JDK 21+ CI legs.
		enabled = JavaVersion.current() >= JavaVersion.VERSION_21
		reports {
			xml.required.set(true)
			html.required.set(true)
		}
	}

	tasks.withType<Pmd>().configureEach {
		reports {
			xml.required.set(true)
			html.required.set(true)
		}
	}

	if (name in libraryProjects) {
		tasks.register<Pmd>("pmdPublicApiReview") {
			description = "Reports large public APIs without failing the correctness gate."
			group = "verification"
			val main = project.extensions.getByType<SourceSetContainer>()["main"]
			setSource(main.allJava)
			classpath = main.compileClasspath + main.output
			ruleSetFiles = rootProject.files("config/pmd/pmd-review.xml")
			ignoreFailures = true
			reports {
				xml.outputLocation.set(project.layout.buildDirectory.file("reports/pmd/pmdPublicApiReview.xml"))
				html.outputLocation.set(project.layout.buildDirectory.file("reports/pmd/pmdPublicApiReview.html"))
			}
		}
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
		finalizedBy(tasks.named<JacocoReport>("jacocoTestReport"))
	}

	tasks.named<JacocoReport>("jacocoTestReport") {
		dependsOn(tasks.withType<Test>())
		reports {
			xml.required.set(true)
			html.required.set(true)
		}
	}

	if (name in libraryProjects) {
		val coverageData = layout.buildDirectory.file("jacoco/test.exec")
		val mainClasses = extensions.getByType<SourceSetContainer>()["main"].output.classesDirs
		val requireCoverageData = tasks.register("requireCoverageData") {
			dependsOn(tasks.named("classes"), tasks.named("test"))
			doLast {
				// Package descriptors have no executable code; new starter classes enter the policy.
				val executableClasses = mainClasses.asFileTree.matching {
					include("**/*.class")
					exclude("**/package-info.class", "**/module-info.class")
				}
				check(executableClasses.isEmpty || coverageData.get().asFile.isFile) {
					"Missing JaCoCo execution data for ${project.name}; run tests before verification."
				}
			}
		}
		val coverageVerification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
			dependsOn(requireCoverageData)
			violationRules {
				rule {
					element = "BUNDLE"
					limit {
						counter = "LINE"
						value = "COVEREDRATIO"
						minimum = coverageLineMinimum
					}
					limit {
						counter = "BRANCH"
						value = "COVEREDRATIO"
						minimum = coverageBranchMinimum
					}
				}
			}
		}
		tasks.named("check") {
			dependsOn(coverageVerification)
		}
	}

	if (name == "openrouter-spring-ai-samples") {
		tasks.withType<Checkstyle>().configureEach {
			configFile = rootProject.file("config/checkstyle/checkstyle-samples.xml")
			// Spring AOT sources are generated, not maintained sample code.
			enabled = JavaVersion.current() >= JavaVersion.VERSION_21 && name in setOf("checkstyleMain", "checkstyleTest")
		}
		tasks.withType<Pmd>().configureEach {
			enabled = name in setOf("pmdMain", "pmdTest")
		}
	}

	if (name in libraryProjects) {
		configure<PublishingExtension> {
			publications {
				create<MavenPublication>("mavenJava") {
					from(components["java"])
					pom {
						url.set(pomProperty("url"))
						scm {
							connection.set(pomProperty("connection"))
							developerConnection.set(pomProperty("developerConnection"))
							url.set(pomProperty("url"))
						}
					}
					versionMapping {
						usage("java-api") {
							fromResolutionOf("runtimeClasspath")
						}
						usage("java-runtime") {
							fromResolutionResult()
						}
					}
				}
			}
		}
	}
}
