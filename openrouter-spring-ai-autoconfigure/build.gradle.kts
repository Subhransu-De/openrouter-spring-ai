description = "@AutoConfiguration and @ConfigurationProperties for the OpenRouter provider."

val archunitVersion = rootProject.extra["archunitVersion"] as String

dependencies {
	api("org.jspecify:jspecify")
	api(project(":openrouter-spring-ai"))
	api("org.springframework.boot:spring-boot-autoconfigure")
	api("org.springframework.boot:spring-boot-http-client")
	api("org.springframework.ai:spring-ai-autoconfigure-retry")
	api("org.springframework.ai:spring-ai-autoconfigure-model-tool")
	api("org.springframework.ai:spring-ai-autoconfigure-model-chat-observation")
	api("org.springframework.ai:spring-ai-autoconfigure-model-embedding-observation")
	api("org.springframework.ai:spring-ai-autoconfigure-model-image-observation")
	api("org.springframework.ai:spring-ai-autoconfigure-model-audio-observation")

	compileOnly("org.springframework.boot:spring-boot-configuration-processor")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	// Writes META-INF/spring-autoconfigure-metadata.properties so Boot can filter these
	// auto-configurations by their class conditions without loading them.
	annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.ai:spring-ai-client-chat")
	testImplementation("io.projectreactor:reactor-test")
	// Supplies ClientAuthorizationException, which the tool failure processor rethrows by
	// class name.
	testImplementation("org.springframework.security:spring-security-oauth2-client")
	testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}

// The configuration processor merges META-INF/additional-spring-configuration-metadata.json
// into the generated metadata, so the compile output depends on that resource too.
// Without this input a build-cache hit can restore metadata from before the file changed.
tasks.compileJava {
	inputs.files(tasks.processResources)
}
