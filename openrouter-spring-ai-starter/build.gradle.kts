description = "Spring Boot starter for OpenRouter with Spring AI. Add this dependency to use the library."

dependencies {
	api(project(":openrouter-spring-ai-autoconfigure"))
	api("org.springframework.ai:spring-ai-client-chat")
	api("org.springframework.ai:spring-ai-autoconfigure-model-chat-client")
	api("org.springframework.ai:spring-ai-autoconfigure-model-chat-memory")
	api("org.springframework.boot:spring-boot-starter-restclient")
	api("org.springframework.boot:spring-boot-starter-webclient")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator")
}
