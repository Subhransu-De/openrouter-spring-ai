pluginManagement {
    val pom = file("../pom.xml").readText()
    fun version(name: String) = Regex("<$name>([^<]+)</$name>").find(pom)!!.groupValues[1]
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        id("org.springframework.boot") version version("spring-boot.version")
        id("org.graalvm.buildtools.native") version version("graalvm-buildtools.version")
    }
}
rootProject.name = "native-smoke-tests"
includeBuild("..")
