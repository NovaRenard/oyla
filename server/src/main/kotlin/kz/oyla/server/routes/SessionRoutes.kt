package kz.oyla.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kz.oyla.server.model.dto.ConnectSessionRequest
import kz.oyla.server.model.dto.CreateSessionRequest
import kz.oyla.server.model.dto.ShowExerciseRequest
import kz.oyla.server.model.dto.StartExerciseRequest
import kz.oyla.server.model.dto.AnswerExerciseRequest
import kz.oyla.server.model.dto.NextExerciseRequest
import kz.oyla.server.service.SessionEventHub
import kz.oyla.server.service.ExerciseService
import kz.oyla.server.service.SessionService

fun Route.sessionRoutes(service: SessionService, exercises: ExerciseService, eventHub: SessionEventHub) {
    route("/api/v1/sessions") {
        post {
            val result = service.create(call.receive<CreateSessionRequest>())
            exercises.ensureDefaultExercisePlan(java.util.UUID.fromString(result.sessionId))
            call.respond(HttpStatusCode.Created, result)
        }
        post("/connect") {
            val result = service.connect(call.receive<ConnectSessionRequest>())
            eventHub.publishChildConnected(java.util.UUID.fromString(result.sessionId))
            call.respond(result)
        }
        get("/{sessionId}") {
            val state = service.getState(
                sessionId = call.parameters["sessionId"].orEmpty(),
                token = call.bearerToken()
            )
            call.respond(state)
        }
        post("/{sessionId}/cancel") {
            val cancelled = service.cancel(
                sessionId = call.parameters["sessionId"].orEmpty(),
                token = call.bearerToken()
            )
            eventHub.publishSessionCancelled(cancelled.id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{sessionId}/complete") {
            val completed = service.complete(
                sessionId = call.parameters["sessionId"].orEmpty(), token = call.bearerToken()
            )
            eventHub.publishSessionCompleted(completed.id)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{sessionId}/exercise/state") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            call.respond(exercises.stateFor(authorized))
        }
        get("/{sessionId}/exercise/specialist") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            call.respond(exercises.specialistExercise(authorized))
        }
        get("/{sessionId}/summary") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            call.respond(exercises.summary(authorized))
        }
        post("/{sessionId}/exercise/show") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            val result = exercises.show(authorized, call.receive<ShowExerciseRequest>())
            eventHub.publishExerciseShown(authorized.session.id, exercises.stateFor(authorized))
            call.respond(result)
        }
        post("/{sessionId}/exercise/start") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            val result = exercises.start(authorized, call.receive<StartExerciseRequest>())
            eventHub.publishExerciseStarted(authorized.session.id, exercises.stateFor(authorized))
            call.respond(result)
        }
        post("/{sessionId}/exercise/answer") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            val result = exercises.answer(authorized, call.receive<AnswerExerciseRequest>())
            eventHub.publishAnswer(authorized.session.id, result)
            if (result.isCorrect) {
                val state = exercises.stateFor(authorized)
                if (state.planCompleted) eventHub.publishExercisePlanCompleted(authorized.session.id, state)
            }
            call.respond(result)
        }
        post("/{sessionId}/exercise/next") {
            val authorized = service.authorize(call.parameters["sessionId"].orEmpty(), call.bearerToken())
            val result = exercises.next(authorized, call.receive<NextExerciseRequest>())
            eventHub.publishExerciseChanged(authorized.session.id, result)
            call.respond(result)
        }
    }
    get("/api/v1/exercises/sound-r-rocket") { call.respond(exercises.getExercise("sound-r-rocket")) }
}

private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.trim()
