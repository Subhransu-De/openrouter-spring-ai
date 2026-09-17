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
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension

plugins {
	base
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
val libraryProjects =
	setOf("openrouter-spring-ai", "openrouter-spring-ai-autoconfigure", "openrouter-spring-ai-starter")

extra["archunitVersion"] = archunitVersion
extra["lombokVersion"] = pomProperty("lombok.version")
extra["twelveMonkeysVersion"] = pomProperty("twelvemonkeys.version")

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
		ruleSets = emptyList()
		ruleSetFiles = rootProject.files("config/pmd/pmd-main.xml", "config/pmd/pmd-test.xml")
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
