plugins {
	id("org.springframework.boot")
	id("org.graalvm.buildtools.native")
}

val lombokVersion: String by rootProject.extra

description = "Repository-local sample applications for the OpenRouter starter. Not published."

dependencies {
	implementation(project(":openrouter-spring-ai-starter"))
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("io.micrometer:micrometer-core")
	implementation("com.twelvemonkeys.imageio:imageio-webp:3.13.1")

	compileOnly("org.springframework.boot:spring-boot-configuration-processor")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	compileOnly("org.projectlombok:lombok:$lombokVersion")
	annotationProcessor("org.projectlombok:lombok:$lombokVersion")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
	archiveClassifier.set("plain")
}

springBoot {
	mainClass.set("de.subhransu.openrouter.springai.garage.GarageApplication")
}

tasks.withType<JavaCompile>().configureEach {
	options.compilerArgs.add("-parameters")
}
