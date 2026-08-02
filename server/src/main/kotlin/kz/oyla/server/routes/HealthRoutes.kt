package kz.oyla.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kz.oyla.server.model.dto.HealthResponse

fun Route.healthRoutes() {
    get("/health") { call.respond(HttpStatusCode.OK, HealthResponse()) }
}
