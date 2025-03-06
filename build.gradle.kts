plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.springframework.ai:spring-ai-bedrock-converse-spring-boot-starter")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0-M6")
    }
}
