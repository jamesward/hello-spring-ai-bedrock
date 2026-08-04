plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
    implementation("com.dashjoin:jsonata:0.9.10")
}

// Two apps live in this module, so the default main class is ambiguous — pin it, and add a
// dedicated run task per app.
springBoot {
    mainClass.set("com.example.demo.synth.SynthAppKt")
}

tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("runFilter") {
    group = "application"
    description = "Run Group A — the response-filtering scenarios (FilterApp)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.demo.filter.FilterAppKt")
}

tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("runSynth") {
    group = "application"
    description = "Run Group B — the synthetic tool system scenarios (SynthApp)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.demo.synth.SynthAppKt")
}
