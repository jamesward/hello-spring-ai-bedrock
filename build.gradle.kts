plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-model-bedrock-converse")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.3")
    }
}
