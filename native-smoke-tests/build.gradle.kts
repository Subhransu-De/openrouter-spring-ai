plugins {
    java
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
}

val pom = file("../pom.xml").readText()
fun version(name: String) = Regex("<$name>([^<]+)</$name>").find(pom)!!.groupValues[1]
repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${version("spring-boot.version")}"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("de.subhransu:openrouter-spring-ai-starter:${version("revision")}")
}
java { sourceCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
springBoot { mainClass.set("example.NativeSmoke") }
graalvmNative { binaries { named("main") { imageName.set("native-smoke") } } }
