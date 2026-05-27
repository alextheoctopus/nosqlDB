plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

val ktorVersion = "3.2.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.datastax.oss:java-driver-core:4.17.0")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("redis.clients:jedis:5.2.0")
    implementation(platform("org.mongodb:mongodb-driver-bom:5.5.0"))
    implementation("org.mongodb:mongodb-driver-kotlin-sync")
    implementation("org.mindrot:jbcrypt:0.4")

    implementation("org.neo4j.driver:neo4j-java-driver:5.26.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}