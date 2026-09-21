pluginManagement {
	val pomText = file("pom.xml").readText()
	fun pomProperty(name: String): String =
		Regex("<${Regex.escape(name)}>([^<]+)</${Regex.escape(name)}>")
			.find(pomText)
			?.groupValues
			?.get(1)
			?: error("Missing <$name> in the authoritative Maven POM")

	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
	plugins {
		id("net.ltgt.errorprone") version pomProperty("error-prone-gradle-plugin.version")
		id("org.springframework.boot") version pomProperty("spring-boot.version")
		id("org.graalvm.buildtools.native") version pomProperty("graalvm-buildtools.version")
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenCentral {
			// Resolve the same published POM contract Maven consumes. Gradle module
			// metadata can expose a wider graph than the corresponding Maven POM.
			metadataSources {
				mavenPom()
				artifact()
			}
		}
	}
}

rootProject.name = "openrouter-spring-ai-parent"

include(
	"openrouter-spring-ai",
	"openrouter-spring-ai-autoconfigure",
	"openrouter-spring-ai-starter",
	"openrouter-spring-ai-samples",
)
