plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.skillsjars.gradle-plugin") version "0.2.0"
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-model-bedrock-converse")

    // Agent Skills, extracted with ./gradlew extractSkillsJars
    skill("com.jamesward:skills:0.0.12")
}

skillsjars {
    outputDir.set(layout.projectDirectory.dir(".kiro/skills"))
}
