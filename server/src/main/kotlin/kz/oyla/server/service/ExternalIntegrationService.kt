package kz.oyla.server.service

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kz.oyla.server.integration.CredentialCipher
import kz.oyla.server.integration.CredentialDecryptionException
import kz.oyla.server.integration.EncryptedCredential
import kz.oyla.server.integration.ExternalCrmClient
import kz.oyla.server.integration.ExternalCrmResult
import kz.oyla.server.integration.IntegrationRateLimiter
import kz.oyla.server.integration.OutboundUrlException
import kz.oyla.server.integration.OutboundUrlPolicy
import kz.oyla.server.model.AuditActorType
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.ExternalIntegrationRecord
import kz.oyla.server.model.ExternalIntegrationStatus
import kz.oyla.server.model.ExternalIntegrationType
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.dto.ChildDataSourceDto
import kz.oyla.server.model.dto.CrmChildImportState
import kz.oyla.server.model.dto.CrmChildPreviewDto
import kz.oyla.server.model.dto.CrmChildrenPreviewResponse
import kz.oyla.server.model.dto.CrmConnectionTestRequest
import kz.oyla.server.model.dto.CrmConnectionTestResponse
import kz.oyla.server.model.dto.CrmConnectionTestStatus
import kz.oyla.server.model.dto.CrmIntegrationDto
import kz.oyla.server.model.dto.CrmSyncResponse
import kz.oyla.server.model.dto.ImportCrmChildrenRequest
import kz.oyla.server.model.dto.ImportCrmChildrenResponse
import kz.oyla.server.model.dto.SaveCrmIntegrationRequest
import kz.oyla.server.model.dto.UpdateCrmIntegrationRequest
import kz.oyla.server.repository.ExternalIntegrationRepository
import org.slf4j.LoggerFactory

class ExternalIntegrationService(
    private val saas: SaasService,
    private val repository: ExternalIntegrationRepository,
    private val cipher: CredentialCipher,
    private val client: ExternalCrmClient,
    private val urlPolicy: OutboundUrlPolicy,
    private val limiter: IntegrationRateLimiter = IntegrationRateLimiter(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(ExternalIntegrationService::class.java)

    suspend fun getCrmIntegration(userId: UUID, centerId: UUID?): CrmIntegrationDto? {
        val context = managementContext(userId, centerId)
        return repository.findCrmIntegration(context.center.id)?.toDto()
    }

    suspend fun testConnection(userId: UUID, centerId: UUID?, request: CrmConnectionTestRequest, ipAddress: String?): CrmConnectionTestResponse {
        val context = managementContext(userId, centerId); rateLimit(context.center.id, "test", 5)
        val current = repository.findCrmIntegration(context.center.id)
        val candidate = candidate(current, request.baseUrl, request.apiKey)
        val result = test(candidate.baseUrl, candidate.apiKey)
        log.info("CRM connection test centerId={} integrationId={} result={}", context.center.id, current?.id, result.status)
        val now = clock.instant()
        val audit = audit(context, "CRM_INTEGRATION_CONNECTION_TESTED", "EXTERNAL_INTEGRATION", current?.id, ipAddress, now, "{\"result\":\"${result.status.name}\"}")
        // A saved-credential test is a health signal for the persisted integration. A candidate
        // key is never written or allowed to change an otherwise working integration on failure.
        if (candidate.usesStoredCredential && current != null) repository.updateConnectionCheck(current.id, context.center.id, result.status == CrmConnectionTestStatus.SUCCESS, now, audit)
        else repository.recordAudit(audit)
        return result
    }

    suspend fun createCrmIntegration(userId: UUID, centerId: UUID?, request: SaveCrmIntegrationRequest, ipAddress: String?): CrmIntegrationDto {
        val context = managementContext(userId, centerId)
        val candidate = candidate(null, request.baseUrl, request.apiKey)
        requireSuccessfulTest(context, candidate, ipAddress)
        val now = clock.instant(); val existing = repository.findCrmIntegration(context.center.id)
        val envelope = cipher.encrypt(candidate.apiKey)
        val record = (existing ?: ExternalIntegrationRecord(UUID.randomUUID(), context.center.id, ExternalIntegrationType.CUSTOM_CRM, "CRM", candidate.baseUrl, null, null, null, ExternalIntegrationStatus.ACTIVE, null, null, now, now)).copy(
            name = request.name?.cleanName() ?: existing?.name ?: "CRM", baseUrl = candidate.baseUrl, encryptedCredential = envelope.ciphertext, credentialNonce = envelope.nonce,
            credentialKeyVersion = envelope.keyVersion, status = ExternalIntegrationStatus.ACTIVE, lastConnectionCheckAt = now, updatedAt = now
        )
        val action = if (existing == null) "CRM_INTEGRATION_CREATED" else "CRM_INTEGRATION_UPDATED"
        val audit = audit(context, action, "EXTERNAL_INTEGRATION", record.id, ipAddress, now)
        val saved = if (existing == null) repository.saveIntegration(record, audit) else repository.updateIntegration(record, audit) ?: throw ApiException.integrationNotFound()
        return saved.toDto()
    }

    suspend fun updateCrmIntegration(userId: UUID, centerId: UUID?, request: UpdateCrmIntegrationRequest, ipAddress: String?): CrmIntegrationDto {
        val context = managementContext(userId, centerId); val existing = repository.findCrmIntegration(context.center.id) ?: throw ApiException.integrationNotFound()
        val candidate = candidate(existing, request.baseUrl ?: existing.baseUrl, request.apiKey)
        requireSuccessfulTest(context, candidate, ipAddress)
        val now = clock.instant()
        val envelope = if (request.apiKey?.trim()?.isNotEmpty() == true) cipher.encrypt(candidate.apiKey) else null
        val updated = existing.copy(
            name = request.name?.cleanName() ?: existing.name, baseUrl = candidate.baseUrl,
            encryptedCredential = envelope?.ciphertext ?: existing.encryptedCredential, credentialNonce = envelope?.nonce ?: existing.credentialNonce,
            credentialKeyVersion = envelope?.keyVersion ?: existing.credentialKeyVersion, status = ExternalIntegrationStatus.ACTIVE, lastConnectionCheckAt = now, updatedAt = now
        )
        return (repository.updateIntegration(updated, audit(context, "CRM_INTEGRATION_UPDATED", "EXTERNAL_INTEGRATION", existing.id, ipAddress, now)) ?: throw ApiException.integrationNotFound()).toDto()
    }

    suspend fun disableCrmIntegration(userId: UUID, centerId: UUID?, ipAddress: String?) {
        val context = managementContext(userId, centerId); val current = repository.findCrmIntegration(context.center.id) ?: throw ApiException.integrationNotFound(); val now = clock.instant()
        if (!repository.disableIntegration(context.center.id, current.id, now, audit(context, "CRM_INTEGRATION_DISABLED", "EXTERNAL_INTEGRATION", current.id, ipAddress, now))) throw ApiException.integrationNotFound()
    }

    suspend fun previewChildren(userId: UUID, centerId: UUID?): CrmChildrenPreviewResponse {
        val context = managementContext(userId, centerId); rateLimit(context.center.id, "preview", 10)
        val integration = activeIntegration(context.center.id); val remote = listRemoteChildren(context, integration, null)
        val links = repository.findLinks(integration.id, remote.map { it.externalId }).associateBy { it.externalId }
        return CrmChildrenPreviewResponse(remote.map { external ->
            val link = links[external.externalId]
            val state = when {
                external.status.name == "ARCHIVED" -> CrmChildImportState.ARCHIVED_EXTERNAL
                link == null -> CrmChildImportState.NOT_IMPORTED
                link.externalUpdatedAt == null || external.externalUpdatedAt.isAfter(link.externalUpdatedAt) -> CrmChildImportState.UPDATE_AVAILABLE
                else -> CrmChildImportState.IMPORTED
            }
            CrmChildPreviewDto(external.externalId, external.firstName, external.lastName, external.birthDate.toString(), if (external.status.name == "ACTIVE") "ACTIVE" else "ARCHIVED", external.externalUpdatedAt.toString(), state, link?.localEntityId?.toString())
        })
    }

    suspend fun importChildren(userId: UUID, centerId: UUID?, request: ImportCrmChildrenRequest, ipAddress: String?): ImportCrmChildrenResponse {
        val context = managementContext(userId, centerId); val integration = activeIntegration(context.center.id)
        val ids = request.externalIds.map { it.trim() }.also { values ->
            if (values.isEmpty() || values.size > MaxImportChildren || values.any { it.isEmpty() || it.length > 255 } || values.toSet().size != values.size) throw ApiException.validation("Выберите от 1 до $MaxImportChildren разных детей CRM")
        }
        val remote = listRemoteChildren(context, integration, ipAddress).associateBy { it.externalId }
        val selected = ids.map { id -> remote[id] ?: throw ApiException.validation("Выбранный ребёнок больше недоступен в CRM") }
        val now = clock.instant()
        val result = repository.importChildren(integration, selected, now, listOf(audit(context, "CRM_IMPORT_COMPLETED", "EXTERNAL_INTEGRATION", integration.id, ipAddress, now, "{\"count\":${selected.size}}")))
        result.importedChildIds.forEach { repository.recordAudit(audit(context, "CRM_CHILD_IMPORTED", "CHILD", it, ipAddress, now)) }
        result.updatedChildIds.forEach { repository.recordAudit(audit(context, "CRM_CHILD_SYNCED", "CHILD", it, ipAddress, now)) }
        return ImportCrmChildrenResponse(result.imported, result.updated, result.skipped)
    }

    suspend fun syncChildren(userId: UUID, centerId: UUID?, ipAddress: String?): CrmSyncResponse {
        val context = managementContext(userId, centerId); rateLimit(context.center.id, "sync", 4)
        val integration = activeIntegration(context.center.id)
        val remote = try { listRemoteChildren(context, integration, ipAddress) } catch (error: ApiException) {
            val now = clock.instant(); repository.recordAudit(audit(context, "CRM_SYNC_FAILED", "EXTERNAL_INTEGRATION", integration.id, ipAddress, now)); throw error
        }
        val now = clock.instant()
        val result = repository.syncChildren(integration, remote, now, listOf(audit(context, "CRM_SYNC_COMPLETED", "EXTERNAL_INTEGRATION", integration.id, ipAddress, now)))
        result.updatedChildIds.forEach { repository.recordAudit(audit(context, "CRM_CHILD_SYNCED", "CHILD", it, ipAddress, now)) }
        result.archivedChildIds.forEach { repository.recordAudit(audit(context, "CRM_CHILD_ARCHIVED", "CHILD", it, ipAddress, now)) }
        result.restoredChildIds.forEach { repository.recordAudit(audit(context, "CRM_CHILD_RESTORED", "CHILD", it, ipAddress, now)) }
        return CrmSyncResponse(result.checked, result.updated, result.archived, result.restored, result.unchanged, result.errors)
    }

    suspend fun childSource(centerId: UUID, childId: UUID): ChildDataSourceDto? = repository.childSource(centerId, childId)?.let {
        ChildDataSourceDto("CRM", it.integrationStatus, it.lastSyncedAt?.toString(), it.integrationStatus == ExternalIntegrationStatus.ACTIVE)
    }
    suspend fun isCrmManagedChild(centerId: UUID, childId: UUID): Boolean = repository.isCrmManagedChild(centerId, childId)
    suspend fun clearCrmArchiveOrigin(centerId: UUID, childId: UUID, now: Instant) = repository.clearCrmArchiveOrigin(centerId, childId, now)

    private suspend fun managementContext(userId: UUID, centerId: UUID?) = saas.requireCenterContext(userId, centerId, ManagementRoles)
    private suspend fun activeIntegration(centerId: UUID): ExternalIntegrationRecord = repository.findActiveCrmIntegration(centerId) ?: throw ApiException.integrationNotConnected()
    private fun rateLimit(centerId: UUID, operation: String, max: Int) { if (!limiter.allow("$operation:$centerId", max)) throw ApiException.rateLimited() }

    private data class CandidateCredential(val baseUrl: String, val apiKey: String, val usesStoredCredential: Boolean)
    private fun candidate(existing: ExternalIntegrationRecord?, baseUrlInput: String?, apiKeyInput: String?): CandidateCredential {
        val baseUrl = baseUrlInput?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.baseUrl ?: throw ApiException.validation("Укажите адрес CRM")
        val normalized = try { urlPolicy.validateBaseUrl(baseUrl).normalized } catch (_: OutboundUrlException) { throw ApiException.validation("Адрес CRM не допускается политикой безопасности") }
        val provided = apiKeyInput?.trim()
        val apiKey = when {
            !provided.isNullOrEmpty() -> provided
            existing?.encryptedCredential != null && existing.credentialNonce != null && existing.credentialKeyVersion != null -> decrypt(existing)
            else -> throw ApiException.validation("Укажите API-ключ CRM")
        }
        if (apiKey.length > MaxApiKeyLength) throw ApiException.validation("API-ключ CRM слишком длинный")
        return CandidateCredential(normalized, apiKey, provided.isNullOrEmpty())
    }
    private fun decrypt(record: ExternalIntegrationRecord): String = try {
        cipher.decrypt(EncryptedCredential(checkNotNull(record.encryptedCredential), checkNotNull(record.credentialNonce), checkNotNull(record.credentialKeyVersion)))
    } catch (_: CredentialDecryptionException) { throw ApiException.integrationCredentialUnavailable() }
    private suspend fun requireSuccessfulTest(context: CenterContext, candidate: CandidateCredential, ipAddress: String?) {
        val outcome = test(candidate.baseUrl, candidate.apiKey); val now = clock.instant()
        log.info("CRM connection test centerId={} integrationId={} result={}", context.center.id, null, outcome.status)
        repository.recordAudit(audit(context, "CRM_INTEGRATION_CONNECTION_TESTED", "EXTERNAL_INTEGRATION", null, ipAddress, now, "{\"result\":\"${outcome.status.name}\"}"))
        if (outcome.status != CrmConnectionTestStatus.SUCCESS) throw ApiException.crmConnectionFailed(outcome.status.name)
    }
    private suspend fun test(baseUrl: String, apiKey: String): CrmConnectionTestResponse = when (val result = client.checkConnection(baseUrl, apiKey)) {
        is ExternalCrmResult.Success -> CrmConnectionTestResponse(CrmConnectionTestStatus.SUCCESS)
        is ExternalCrmResult.Failure -> CrmConnectionTestResponse(result.status)
    }
    private suspend fun listRemoteChildren(context: CenterContext, integration: ExternalIntegrationRecord, ipAddress: String?): List<ExternalCrmChild> {
        val key = decrypt(integration)
        return when (val result = client.listChildren(integration.baseUrl, key)) {
            is ExternalCrmResult.Success -> {
                log.info("CRM children request centerId={} integrationId={} httpStatus={} durationMs={} count={}", context.center.id, integration.id, result.httpStatus, result.durationMillis, result.value.size)
                result.value
            }
            is ExternalCrmResult.Failure -> {
                log.warn("CRM children request failed centerId={} integrationId={} result={}", context.center.id, integration.id, result.status)
                throw ApiException.crmConnectionFailed(result.status.name)
            }
        }
    }
    private fun audit(context: CenterContext, action: String, entityType: String, entityId: UUID?, ipAddress: String?, now: Instant, metadata: String = "{}") =
        AuditLogRecord(UUID.randomUUID(), context.center.id, AuditActorType.USER, context.user.id, action, entityType, entityId, metadata, ipAddress, now)
    private fun ExternalIntegrationRecord.toDto() = CrmIntegrationDto(id.toString(), type, name, baseUrl, status, encryptedCredential != null, lastConnectionCheckAt?.toString(), lastSuccessfulSyncAt?.toString())
    private fun String.cleanName(): String = trim().takeIf { it.isNotEmpty() && it.length <= 160 } ?: throw ApiException.validation("Название интеграции заполнено некорректно")
    private companion object { val ManagementRoles = setOf(MembershipRole.OWNER, MembershipRole.ADMIN); const val MaxApiKeyLength = 4096; const val MaxImportChildren = 500 }
}
