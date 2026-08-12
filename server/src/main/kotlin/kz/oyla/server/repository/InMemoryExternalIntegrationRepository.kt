package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ChildRecord
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.ExternalEntityLinkRecord
import kz.oyla.server.model.ExternalEntityType
import kz.oyla.server.model.ExternalIntegrationRecord
import kz.oyla.server.model.ExternalIntegrationStatus
import kz.oyla.server.model.ImportedChildSource

class InMemoryExternalIntegrationRepository(private val childrenRepository: SaasRepository) : ExternalIntegrationRepository {
    private val mutex = Mutex()
    private val integrations = linkedMapOf<UUID, ExternalIntegrationRecord>()
    private val links = linkedMapOf<UUID, ExternalEntityLinkRecord>()
    val audits = mutableListOf<AuditLogRecord>()

    override suspend fun findCrmIntegration(centerId: UUID): ExternalIntegrationRecord? = mutex.withLock {
        integrations.values.filter { it.centerId == centerId }.sortedWith(compareByDescending<ExternalIntegrationRecord> { it.status == ExternalIntegrationStatus.ACTIVE }.thenByDescending { it.updatedAt }).firstOrNull()
    }
    override suspend fun findActiveCrmIntegration(centerId: UUID): ExternalIntegrationRecord? = mutex.withLock {
        integrations.values.firstOrNull { it.centerId == centerId && it.status == ExternalIntegrationStatus.ACTIVE }
    }
    override suspend fun saveIntegration(record: ExternalIntegrationRecord, audit: AuditLogRecord): ExternalIntegrationRecord = mutex.withLock {
        integrations[record.id] = record; audits += audit; record
    }
    override suspend fun updateIntegration(record: ExternalIntegrationRecord, audit: AuditLogRecord): ExternalIntegrationRecord? = mutex.withLock {
        if (integrations[record.id]?.centerId != record.centerId) null else record.also { integrations[it.id] = it; audits += audit }
    }
    override suspend fun updateConnectionCheck(id: UUID, centerId: UUID, success: Boolean, now: Instant, audit: AuditLogRecord) = mutex.withLock {
        integrations[id]?.takeIf { it.centerId == centerId }?.let { current ->
            integrations[id] = current.copy(status = if (success) ExternalIntegrationStatus.ACTIVE else ExternalIntegrationStatus.ERROR, lastConnectionCheckAt = now, updatedAt = now)
        }; audits += audit
    }
    override suspend fun disableIntegration(centerId: UUID, integrationId: UUID, now: Instant, audit: AuditLogRecord): Boolean = mutex.withLock {
        val current = integrations[integrationId]?.takeIf { it.centerId == centerId } ?: return@withLock false
        integrations[integrationId] = current.copy(status = ExternalIntegrationStatus.DISABLED, encryptedCredential = null, credentialNonce = null, credentialKeyVersion = null, updatedAt = now)
        audits += audit; true
    }
    override suspend fun findLinks(integrationId: UUID, externalIds: Collection<String>): List<ExternalEntityLinkRecord> = mutex.withLock {
        links.values.filter { it.integrationId == integrationId && it.externalId in externalIds }
    }
    override suspend fun childSource(centerId: UUID, childId: UUID): ImportedChildSource? = mutex.withLock {
        val link = links.values.firstOrNull { it.centerId == centerId && it.localEntityId == childId } ?: return@withLock null
        val integration = integrations[link.integrationId] ?: return@withLock null
        ImportedChildSource(integration.id, integration.name, integration.status, link.lastSyncedAt)
    }
    override suspend fun isCrmManagedChild(centerId: UUID, childId: UUID): Boolean = mutex.withLock {
        links.values.any { link -> link.centerId == centerId && link.localEntityId == childId && integrations[link.integrationId]?.status == ExternalIntegrationStatus.ACTIVE }
    }
    override suspend fun clearCrmArchiveOrigin(centerId: UUID, childId: UUID, now: Instant) = mutex.withLock {
        links.values.filter { it.centerId == centerId && it.localEntityId == childId }.forEach { link -> links[link.id] = link.copy(crmArchivedLocal = false, updatedAt = now) }
    }
    override suspend fun importChildren(integration: ExternalIntegrationRecord, children: List<ExternalCrmChild>, now: Instant, audits: List<AuditLogRecord>): CrmImportResult = mutex.withLock {
        var imported = 0; var updated = 0; var skipped = 0; val importedIds = mutableListOf<UUID>(); val updatedIds = mutableListOf<UUID>()
        children.forEach { external ->
            val link = links.values.firstOrNull { it.integrationId == integration.id && it.entityType == ExternalEntityType.CHILD && it.externalId == external.externalId }
            if (link == null) {
                val child = ChildRecord(UUID.randomUUID(), integration.centerId, external.firstName, external.lastName, external.birthDate, external.status, now, now)
                childrenRepository.createChild(child)
                val linkId = UUID.randomUUID()
                links[linkId] = ExternalEntityLinkRecord(linkId, integration.centerId, integration.id, ExternalEntityType.CHILD, external.externalId, child.id, now, external.externalUpdatedAt, external.status == ChildStatus.ARCHIVED, now, now)
                imported++; importedIds += child.id
            } else {
                val current = checkNotNull(childrenRepository.findChild(integration.centerId, link.localEntityId))
                val changed = current.firstName != external.firstName || current.lastName != external.lastName || current.birthDate != external.birthDate || current.status != external.status
                if (changed) { childrenRepository.updateChild(current.copy(firstName = external.firstName, lastName = external.lastName, birthDate = external.birthDate, status = external.status, updatedAt = now)); updated++; updatedIds += current.id } else skipped++
                links[link.id] = link.copy(lastSyncedAt = now, externalUpdatedAt = external.externalUpdatedAt, crmArchivedLocal = external.status == ChildStatus.ARCHIVED, updatedAt = now)
            }
        }
        integrations[integration.id] = checkNotNull(integrations[integration.id]).copy(lastSuccessfulSyncAt = now, updatedAt = now)
        audits.forEach { this.audits += it }
        CrmImportResult(imported, updated, skipped, importedIds, updatedIds)
    }
    override suspend fun syncChildren(integration: ExternalIntegrationRecord, remoteChildren: List<ExternalCrmChild>, now: Instant, audits: List<AuditLogRecord>): CrmSyncResult = mutex.withLock {
        val remote = remoteChildren.associateBy { it.externalId }; val linked = links.values.filter { it.integrationId == integration.id && it.entityType == ExternalEntityType.CHILD }
        var updated = 0; var archived = 0; var restored = 0; var unchanged = 0; val updatedIds = mutableListOf<UUID>(); val archivedIds = mutableListOf<UUID>(); val restoredIds = mutableListOf<UUID>()
        linked.forEach { link ->
            val external = remote[link.externalId] ?: run { unchanged++; return@forEach }
            val current = checkNotNull(childrenRepository.findChild(integration.centerId, link.localEntityId)); var changed = false; var status = current.status; var crmArchived = link.crmArchivedLocal
            val crmArchivedNow = external.status == ChildStatus.ARCHIVED && current.status == ChildStatus.ACTIVE
            val crmRestoredNow = external.status == ChildStatus.ACTIVE && current.status == ChildStatus.ARCHIVED && link.crmArchivedLocal
            if (crmArchivedNow) { status = ChildStatus.ARCHIVED; crmArchived = true; archived++; archivedIds += current.id; changed = true }
            if (crmRestoredNow) { status = ChildStatus.ACTIVE; crmArchived = false; restored++; restoredIds += current.id; changed = true }
            val fieldsChanged = current.firstName != external.firstName || current.lastName != external.lastName || current.birthDate != external.birthDate
            if (fieldsChanged) changed = true
            if (changed) { childrenRepository.updateChild(current.copy(firstName = external.firstName, lastName = external.lastName, birthDate = external.birthDate, status = status, updatedAt = now)); if (fieldsChanged && !crmArchivedNow && !crmRestoredNow) { updated++; updatedIds += current.id } }
            else unchanged++
            links[link.id] = link.copy(lastSyncedAt = now, externalUpdatedAt = external.externalUpdatedAt, crmArchivedLocal = crmArchived, updatedAt = now)
        }
        integrations[integration.id] = checkNotNull(integrations[integration.id]).copy(lastSuccessfulSyncAt = now, updatedAt = now)
        audits.forEach { this.audits += it }
        CrmSyncResult(linked.size, updated, archived, restored, unchanged, updatedChildIds = updatedIds, archivedChildIds = archivedIds, restoredChildIds = restoredIds)
    }
    override suspend fun recordAudit(record: AuditLogRecord) = mutex.withLock { audits += record }
}
