description = "OpenRouterApi client, DTOs, options, mappers, ChatModel, EmbeddingModel."

val archunitVersion = rootProject.extra["archunitVersion"] as String

dependencies {
	api("org.jspecify:jspecify")
	api("org.springframework.ai:spring-ai-model")
	api("org.springframework.ai:spring-ai-retry")
	api("org.springframework:spring-web")
	api("org.springframework:spring-webflux")
	api("tools.jackson.core:jackson-databind")

	testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
	testImplementation("org.springframework.ai:spring-ai-client-chat")
	testImplementation("io.micrometer:micrometer-observation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("ch.qos.logback:logback-classic")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}

tasks.withType<Test>().configureEach {
	systemProperty("compatibility.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

// Tool dependencies are resolved only by the explicitly requested mutation task.
val mutationTool = configurations.create("mutationTool") {
	isCanBeConsumed = false
}
dependencies {
	mutationTool("org.pitest:pitest-command-line:${rootProject.extra["pitestVersion"]}")
	mutationTool("org.pitest:pitest-junit5-plugin:${rootProject.extra["pitestJunitVersion"]}")
}

tasks.register<JavaExec>("mutation") {
	group = "verification"
	description = "Run the focused PIT mutation gate with synthetic core tests"
	dependsOn(tasks.testClasses)
	classpath = mutationTool + sourceSets.test.get().runtimeClasspath
	mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
	doFirst {
		setArgs(listOf(
			"--reportDir", layout.buildDirectory.dir("reports/pitest").get().asFile.absolutePath,
			"--sourceDirs", sourceSets.main.get().allJava.srcDirs.joinToString(",") { it.absolutePath },
			"--targetClasses", providers.gradleProperty("mutation.targets").getOrElse(rootProject.extra["mutationTargets"] as String),
			"--targetTests", "de.subhransu.openrouter.springai.*",
			"--mutableCodePaths", sourceSets.main.get().output.classesDirs.asPath,
			"--threads", providers.gradleProperty("mutation.threads").getOrElse(rootProject.extra["mutationThreads"] as String),
			"--mutators", "DEFAULTS",
			"--mutationThreshold", "80",
			"--failWhenNoMutations", "true",
			"--outputFormats", "HTML,XML",
			"--timestampedReports", "false"
		))
	}
}
