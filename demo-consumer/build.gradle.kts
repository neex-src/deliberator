// The demo consumer: consumes the workbench's events and writes what a real service would —
// log lines and database rows — so the whole loop can be watched in one buffer.
plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.apache.kafka:kafka-clients:3.9.2")
    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:url-connection-client")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.google.code.gson:gson:2.13.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "com.neex.tellij.demo.MainKt"
}
