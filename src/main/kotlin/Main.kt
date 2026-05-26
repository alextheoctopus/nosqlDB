import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val config = loadConfig()
    println("EventHub started on port=${config.port}, host=${config.host}")

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}
