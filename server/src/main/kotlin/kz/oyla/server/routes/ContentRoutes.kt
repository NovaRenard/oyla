package kz.oyla.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import kz.oyla.server.config.DeviceTokenPrincipal
import kz.oyla.server.config.WebUserPrincipal
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.MediaType
import kz.oyla.server.model.dto.CreateExerciseRequest
import kz.oyla.server.model.dto.CreateLessonTemplateRequest
import kz.oyla.server.model.dto.ReplaceLessonTemplateItemsRequest
import kz.oyla.server.model.dto.UpdateExerciseRequest
import kz.oyla.server.model.dto.UpdateLessonTemplateRequest
import kz.oyla.server.repository.ContentExerciseFilter
import kz.oyla.server.repository.LessonTemplateFilter
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.ContentService
import kz.oyla.server.service.SaasService

fun Route.contentRoutes(saas: SaasService, content: ContentService) {
    authenticate("web-jwt") {
        route("/api/v1/exercises") {
            get {
                val context = call.contentContext(saas)
                call.respond(content.listExercises(context, ContentExerciseFilter(call.contentEnum<ContentOwnership>("ownership"), call.contentEnum<ContentStatus>("status"), call.contentEnum<ActivityType>("activityType"), call.request.queryParameters["search"])))
            }
            post { val context=call.contentContext(saas);call.respond(HttpStatusCode.Created,content.createExercise(context,call.receive<CreateExerciseRequest>(),call.contentIp())) }
            get("/{id}") { val context=call.contentContext(saas);call.respond(content.exercise(context,call.contentUuid("id"))) }
            patch("/{id}") { val context=call.contentContext(saas);call.respond(content.updateExercise(context,call.contentUuid("id"),call.receive<UpdateExerciseRequest>(),call.contentIp())) }
            post("/{id}/archive") { val context=call.contentContext(saas);call.respond(content.archiveExercise(context,call.contentUuid("id"),false,call.contentIp())) }
            post("/{id}/restore") { val context=call.contentContext(saas);call.respond(content.archiveExercise(context,call.contentUuid("id"),true,call.contentIp())) }
            post("/{id}/duplicate") { val context=call.contentContext(saas);call.respond(HttpStatusCode.Created,content.duplicateExercise(context,call.contentUuid("id"),call.contentIp())) }
        }
        route("/api/v1/lesson-templates") {
            get { val context=call.contentContext(saas);call.respond(content.listTemplates(context,LessonTemplateFilter(call.contentEnum<ContentOwnership>("ownership"),call.contentEnum<ContentStatus>("status"),call.request.queryParameters["search"]))) }
            post { val context=call.contentContext(saas);call.respond(HttpStatusCode.Created,content.createTemplate(context,call.receive<CreateLessonTemplateRequest>(),call.contentIp())) }
            get("/{id}") { val context=call.contentContext(saas);call.respond(content.template(context,call.contentUuid("id"))) }
            patch("/{id}") { val context=call.contentContext(saas);call.respond(content.updateTemplate(context,call.contentUuid("id"),call.receive<UpdateLessonTemplateRequest>(),call.contentIp())) }
            put("/{id}/items") { val context=call.contentContext(saas);call.respond(content.replaceTemplateItems(context,call.contentUuid("id"),call.receive<ReplaceLessonTemplateItemsRequest>().items,call.contentIp())) }
            post("/{id}/archive") { val context=call.contentContext(saas);call.respond(content.archiveTemplate(context,call.contentUuid("id"),false,call.contentIp())) }
            post("/{id}/restore") { val context=call.contentContext(saas);call.respond(content.archiveTemplate(context,call.contentUuid("id"),true,call.contentIp())) }
            post("/{id}/duplicate") { val context=call.contentContext(saas);call.respond(HttpStatusCode.Created,content.duplicateTemplate(context,call.contentUuid("id"),call.contentIp())) }
        }
        route("/api/v1/media") {
            post("/images") { call.upload(content,saas,MediaType.IMAGE) }
            post("/audio") { call.upload(content,saas,MediaType.AUDIO) }
        }
    }

    // A file is private to a centre. Either device or web authentication can read it;
    // the first successful provider yields its centre identity without public URLs.
    authenticate("web-jwt", "device-token", strategy = AuthenticationStrategy.FirstSuccessful) {
        get("/api/v1/media/{id}") {
            val centerId = call.principal<WebUserPrincipal>()?.let { saas.requireCenterContext(it.userId,it.activeCenterId).center.id }
                ?: call.principal<DeviceTokenPrincipal>()?.device?.let { saas.deviceContentCenter(it) } ?: throw ApiException.unauthorized()
            val (asset,input)=content.openMedia(centerId,call.contentUuid("id"))
            input.use { call.respondBytes(it.readBytes(), ContentType.parse(asset.mimeType)) }
        }
    }
}

private suspend fun ApplicationCall.upload(content: ContentService, saas: SaasService, type: MediaType) {
    val context=contentContext(saas);var result: Any?=null
    val multipart = receiveMultipart()
    while (true) {
        val part = multipart.readPart() ?: break
        try {
            if(part is PartData.FileItem && part.name=="file" && result==null) {
                result=content.upload(context,type,part.originalFileName ?: "upload",part.contentType?.toString(),part.provider().toInputStream(),contentIp())
            }
        } finally { part.dispose() }
    }
    respond(HttpStatusCode.Created,result ?: throw ApiException.validation("Нужен файл в поле file"))
}
private suspend fun ApplicationCall.contentContext(saas:SaasService) = principal<WebUserPrincipal>()?.let { saas.requireCenterContext(it.userId,it.activeCenterId) } ?: throw ApiException.unauthorized()
private fun ApplicationCall.contentUuid(name:String):UUID=parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ApiException.validation("Некорректный идентификатор")
private inline fun <reified T:Enum<T>> ApplicationCall.contentEnum(name:String):T?=request.queryParameters[name]?.let { raw -> enumValues<T>().firstOrNull { it.name==raw.uppercase() } ?: throw ApiException.validation("Некорректный параметр $name") }
private fun ApplicationCall.contentIp():String?=request.local.remoteHost.takeIf { it.length<=64 }
