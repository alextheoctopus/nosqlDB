plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}
val ktorVersion = "3.2.0"

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))

    // Ktor - Движок сервера (обязательно выбрать один, Netty - популярный выбор)
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    // Ktor - Основные компоненты сервера (необходимы для базовой работы Ktor)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    // Ktor - Хост-компоненты (необходимы для функции embeddedServer)
    implementation("io.ktor:ktor-server-host-common:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("ch.qos.logback:logback-classic:1.4.14")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}
