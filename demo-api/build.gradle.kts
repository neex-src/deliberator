// The service under test for Tellij's Java features: a Spring MVC controller whose interface
// carries the mapping + OpenAPI annotations. Runs on the host (demo/up.sh api) with JDWP open.
plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.neex.demo"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    runtimeOnly("org.postgresql:postgresql")
}

tasks.bootJar {
    archiveFileName = "demo-api.jar"
}
