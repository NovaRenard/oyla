package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.ExternalEntityLinkRecord
import kz.oyla.server.model.ExternalIntegrationRecord
import kz.oyla.server.model.ImportedChildSource

data class CrmImportResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val importedChildIds: List<UUID> = emptyList(),
    val updatedChildIds: List<UUID> = emptyList()
)
data class CrmSyncResult(
    val checked: Int,
    val updated: Int,
    val archived: Int,
    val restored: Int,
    val unchanged: Int,
    val errors: Int = 0,
    val updatedChildIds: List<UUID> = emptyList(),
    val archivedChildIds: List<UUID> = emptyList(),
    val restoredChildIds: List<UUID> = emptyList()
)

/**
 * Storage boundary for generic integrations. It deliberately owns the atomic
 * child/link mutation so an import never leaves an unlinked local child behind.
 */
interface ExternalIntegrationRepository {
    suspend fun findCrmIntegration(centerId: UUID): ExternalIntegrationRecord?
    suspend fun findActiveCrmIntegration(centerId: UUID): ExternalIntegrationRecord?
    suspend fun saveIntegration(record: ExternalIntegrationRecord, audit: AuditLogRecord): ExternalIntegrationRecord
    suspend fun updateIntegration(record: ExternalIntegrationRecord, audit: AuditLogRecord): ExternalIntegrationRecord?
    suspend fun updateConnectionCheck(id: UUID, centerId: UUID, success: Boolean, now: Instant, audit: AuditLogRecord)
    suspend fun disableIntegration(centerId: UUID, integrationId: UUID, now: Instant, audit: AuditLogRecord): Boolean
    suspend fun findLinks(integrationId: UUID, externalIds: Collection<String>): List<ExternalEntityLinkRecord>
    suspend fun childSource(centerId: UUID, childId: UUID): ImportedChildSource?
    suspend fun isCrmManagedChild(centerId: UUID, childId: UUID): Boolean
    suspend fun clearCrmArchiveOrigin(centerId: UUID, childId: UUID, now: Instant)
    suspend fun importChildren(
        integration: ExternalIntegrationRecord,
        children: List<ExternalCrmChild>,
        now: Instant,
        audits: List<AuditLogRecord>
    ): CrmImportResult
    suspend fun syncChildren(
        integration: ExternalIntegrationRecord,
        remoteChildren: List<ExternalCrmChild>,
        now: Instant,
        audits: List<AuditLogRecord>
    ): CrmSyncResult
    suspend fun recordAudit(record: AuditLogRecord)
}
