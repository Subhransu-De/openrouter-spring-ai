description = "@AutoConfiguration and @ConfigurationProperties for the OpenRouter provider."

val archunitVersion = rootProject.extra["archunitVersion"] as String

dependencies {
	api(project(":openrouter-spring-ai"))
	api("org.springframework.boot:spring-boot-autoconfigure")
	api("org.springframework.boot:spring-boot-http-client")
	api("org.springframework.ai:spring-ai-autoconfigure-retry")
	api("org.springframework.ai:spring-ai-autoconfigure-model-tool")
	api("org.springframework.ai:spring-ai-autoconfigure-model-chat-observation")
	api("org.springframework.ai:spring-ai-autoconfigure-model-embedding-observation")
	api("org.springframework.ai:spring-ai-autoconfigure-model-image-observation")

	compileOnly("org.springframework.boot:spring-boot-configuration-processor")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.ai:spring-ai-client-chat")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}
