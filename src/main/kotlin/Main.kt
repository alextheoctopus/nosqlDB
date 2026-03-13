import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

fun main() {
    val port = System.getenv("APP_PORT")
    val host = System.getenv("APP_HOST")
    println("EventHub started on port=$port, dbHost=$host")
    embeddedServer(Netty, port = port.toInt(), host = host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {

    install(ContentNegotiation) {
        json()
    }

    routing {
        //корневой url
        get("/") {
            call.respondText("It's root url :)")
        }
        //проверка health
        get("/health") {
            call.respond(HealthResponse("ok"))
        }
    }
}