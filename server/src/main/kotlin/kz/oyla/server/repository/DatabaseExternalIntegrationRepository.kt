package kz.oyla.server.repository

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ChildRecord
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.ExternalEntityLinkRecord
import kz.oyla.server.model.ExternalEntityType
import kz.oyla.server.model.ExternalIntegrationRecord
import kz.oyla.server.model.ExternalIntegrationStatus
import kz.oyla.server.model.ExternalIntegrationType
import kz.oyla.server.model.ImportedChildSource
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

class DatabaseExternalIntegrationRepository : ExternalIntegrationRepository {
    override suspend fun findCrmIntegration(centerId: UUID): ExternalIntegrationRecord? = database {
        connection.findIntegration(
            """SELECT * FROM external_integrations WHERE center_id = ? AND type = 'CUSTOM_CRM'
               ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'ERROR' THEN 1 ELSE 2 END, updated_at DESC LIMIT 1"""
        ) { setObject(1, centerId) }
    }

    override suspend fun findActiveCrmIntegration(centerId: UUID): ExternalIntegrationRecord? = database {
        connection.findIntegration("SELECT * FROM external_integrations WHERE center_id = ? AND type = 'CUSTOM_CRM' AND status = 'ACTIVE' ORDER BY updated_at DESC LIMIT 1") { setObject(1, centerId) }
    }

    override suspend fun saveIntegration(record: ExternalIntegrationRecord, audit: AuditLogRecord): ExternalIntegrationRecord = database {
        connection.prepareStatement(
            """INSERT INTO external_integrations (id, center_id, type, name, base_url, encrypted_credential, credential_nonce, credential_key_version, status, last_connection_check_at, last_successful_sync_at, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement -> statement.bindIntegration(record); statement.executeUpdate() }
        connection.insertAudit(audit); record
    }

    override suspend fun updateIntegration(record: ExternalIntegrationRecord, audit: AuditLogRecord): ExternalIntegrationRecord? = database {
        val updated = connection.prepareStatement(
            """UPDATE external_integrations SET name = ?, base_url = ?, encrypted_credential = ?, credential_nonce = ?, credential_key_version = ?, status = ?, last_connection_check_at = ?, last_successful_sync_at = ?, updated_at = ?
               WHERE id = ? AND center_id = ? AND type = 'CUSTOM_CRM'"""
        ).use { statement ->
            statement.setString(1, record.name); statement.setString(2, record.baseUrl); statement.setString(3, record.encryptedCredential); statement.setString(4, record.credentialNonce)
            statement.setString(5, record.credentialKeyVersion); statement.setString(6, record.status.name); statement.setInstant(7, record.lastConnectionCheckAt); statement.setInstant(8, record.lastSuccessfulSyncAt)
            statement.setInstant(9, record.updatedAt); statement.setObject(10, record.id); statement.setObject(11, record.centerId); statement.executeUpdate()
        }
        if (updated != 1) null else record.also { connection.insertAudit(audit) }
    }

    override suspend fun updateConnectionCheck(id: UUID, centerId: UUID, success: Boolean, now: Instant, audit: AuditLogRecord) = database {
        connection.prepareStatement(
            """UPDATE external_integrations SET status = ?, last_connection_check_at = ?, updated_at = ?
               WHERE id = ? AND center_id = ? AND type = 'CUSTOM_CRM'"""
        ).use { statement ->
            statement.setString(1, if (success) ExternalIntegrationStatus.ACTIVE.name else ExternalIntegrationStatus.ERROR.name); statement.setInstant(2, now); statement.setInstant(3, now)
            statement.setObject(4, id); statement.setObject(5, centerId); statement.executeUpdate()
        }
        connection.insertAudit(audit)
        Unit
    }

    override suspend fun disableIntegration(centerId: UUID, integrationId: UUID, now: Instant, audit: AuditLogRecord): Boolean = database {
        val changed = connection.prepareStatement(
            """UPDATE external_integrations SET status = 'DISABLED', encrypted_credential = NULL, credential_nonce = NULL, credential_key_version = NULL, updated_at = ?
               WHERE id = ? AND center_id = ? AND type = 'CUSTOM_CRM' AND status <> 'DISABLED'"""
        ).use { statement -> statement.setInstant(1, now); statement.setObject(2, integrationId); statement.setObject(3, centerId); statement.executeUpdate() == 1 }
        if (changed) connection.insertAudit(audit); changed
    }

    override suspend fun findLinks(integrationId: UUID, externalIds: Collection<String>): List<ExternalEntityLinkRecord> = database {
        if (externalIds.isEmpty()) return@database emptyList()
        val placeholders = externalIds.joinToString(",") { "?" }
        connection.prepareStatement("SELECT * FROM external_entity_links WHERE integration_id = ? AND entity_type = 'CHILD' AND external_id IN ($placeholders)").use { statement ->
            statement.setObject(1, integrationId); externalIds.forEachIndexed { index, id -> statement.setString(index + 2, id) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toLink()) } }
        }
    }

    override suspend fun childSource(centerId: UUID, childId: UUID): ImportedChildSource? = database {
        connection.prepareStatement(
            """SELECT i.id AS integration_id, i.name AS integration_name, i.status AS integration_status, l.last_synced_at
               FROM external_entity_links l JOIN external_integrations i ON i.id = l.integration_id
               WHERE l.center_id = ? AND l.local_entity_id = ? AND l.entity_type = 'CHILD'
               ORDER BY i.updated_at DESC LIMIT 1"""
        ).use { statement ->
            statement.setObject(1, centerId); statement.setObject(2, childId); statement.executeQuery().use { result ->
                if (!result.next()) null else ImportedChildSource(result.getObject("integration_id", UUID::class.java), result.getString("integration_name"), ExternalIntegrationStatus.valueOf(result.getString("integration_status")), result.getTimestamp("last_synced_at")?.toInstant())
            }
        }
    }

    override suspend fun isCrmManagedChild(centerId: UUID, childId: UUID): Boolean = database {
        connection.prepareStatement(
            """SELECT EXISTS(SELECT 1 FROM external_entity_links l JOIN external_integrations i ON i.id = l.integration_id
               WHERE l.center_id = ? AND l.local_entity_id = ? AND l.entity_type = 'CHILD' AND i.type = 'CUSTOM_CRM' AND i.status = 'ACTIVE')"""
        ).use { statement -> statement.setObject(1, centerId); statement.setObject(2, childId); statement.executeQuery().use { it.next(); it.getBoolean(1) } }
    }

    override suspend fun clearCrmArchiveOrigin(centerId: UUID, childId: UUID, now: Instant) = database {
        connection.prepareStatement("UPDATE external_entity_links SET crm_archived_local = FALSE, updated_at = ? WHERE center_id = ? AND local_entity_id = ? AND entity_type = 'CHILD'").use { statement ->
            statement.setInstant(1, now); statement.setObject(2, centerId); statement.setObject(3, childId); statement.executeUpdate()
        }
        Unit
    }

    override suspend fun importChildren(integration: ExternalIntegrationRecord, children: List<ExternalCrmChild>, now: Instant, audits: List<AuditLogRecord>): CrmImportResult = database {
        connection.lockIntegration(integration.id, integration.centerId)
        val existing = connection.linksForUpdate(integration.id, children.map { it.externalId }).associateBy { it.externalId }
        var imported = 0; var updated = 0; var skipped = 0; val importedIds = mutableListOf<UUID>(); val updatedIds = mutableListOf<UUID>()
        children.forEach { external ->
            val link = existing[external.externalId]
            if (link == null) {
                val child = ChildRecord(UUID.randomUUID(), integration.centerId, external.firstName, external.lastName, external.birthDate, external.status, now, now)
                connection.insertChild(child)
                connection.insertLink(ExternalEntityLinkRecord(UUID.randomUUID(), integration.centerId, integration.id, ExternalEntityType.CHILD, external.externalId, child.id, now, external.externalUpdatedAt, external.status == ChildStatus.ARCHIVED, now, now))
                imported++; importedIds += child.id
            } else {
                val child = connection.findChildForUpdate(integration.centerId, link.localEntityId) ?: error("External entity link points outside its center")
                val changed = child.firstName != external.firstName || child.lastName != external.lastName || child.birthDate != external.birthDate || child.status != external.status
                if (changed) { connection.updateChildFromCrm(child.copy(firstName = external.firstName, lastName = external.lastName, birthDate = external.birthDate, status = external.status, updatedAt = now)); updated++; updatedIds += child.id } else skipped++
                connection.updateLink(link.copy(lastSyncedAt = now, externalUpdatedAt = external.externalUpdatedAt, crmArchivedLocal = external.status == ChildStatus.ARCHIVED, updatedAt = now))
            }
        }
        connection.updateSuccessfulSync(integration.id, integration.centerId, now)
        audits.forEach { audit -> connection.insertAudit(audit) }
        CrmImportResult(imported, updated, skipped, importedIds, updatedIds)
    }

    override suspend fun syncChildren(integration: ExternalIntegrationRecord, remoteChildren: List<ExternalCrmChild>, now: Instant, audits: List<AuditLogRecord>): CrmSyncResult = database {
        connection.lockIntegration(integration.id, integration.centerId)
        val remote = remoteChildren.associateBy { it.externalId }
        val linked = connection.linksForIntegrationForUpdate(integration.id)
        var updated = 0; var archived = 0; var restored = 0; var unchanged = 0; val updatedIds = mutableListOf<UUID>(); val archivedIds = mutableListOf<UUID>(); val restoredIds = mutableListOf<UUID>()
        linked.forEach { link ->
            val external = remote[link.externalId] ?: run { unchanged++; return@forEach }
            val current = connection.findChildForUpdate(integration.centerId, link.localEntityId) ?: error("External entity link points outside its center")
            val crmArchivedNow = external.status == ChildStatus.ARCHIVED && current.status == ChildStatus.ACTIVE
            val crmRestoredNow = external.status == ChildStatus.ACTIVE && current.status == ChildStatus.ARCHIVED && link.crmArchivedLocal
            val targetStatus = when {
                crmArchivedNow -> ChildStatus.ARCHIVED
                crmRestoredNow -> ChildStatus.ACTIVE
                else -> current.status
            }
            val fieldsChanged = current.firstName != external.firstName || current.lastName != external.lastName || current.birthDate != external.birthDate
            if (crmArchivedNow) { archived++; archivedIds += current.id }
            if (crmRestoredNow) { restored++; restoredIds += current.id }
            if (fieldsChanged && !crmArchivedNow && !crmRestoredNow) { updated++; updatedIds += current.id }
            if (!crmArchivedNow && !crmRestoredNow && !fieldsChanged) unchanged++
            if (crmArchivedNow || crmRestoredNow || fieldsChanged) connection.updateChildFromCrm(current.copy(firstName = external.firstName, lastName = external.lastName, birthDate = external.birthDate, status = targetStatus, updatedAt = now))
            connection.updateLink(link.copy(lastSyncedAt = now, externalUpdatedAt = external.externalUpdatedAt, crmArchivedLocal = when { crmArchivedNow -> true; crmRestoredNow -> false; else -> link.crmArchivedLocal }, updatedAt = now))
        }
        connection.updateSuccessfulSync(integration.id, integration.centerId, now)
        audits.forEach { audit -> connection.insertAudit(audit) }
        CrmSyncResult(linked.size, updated, archived, restored, unchanged, updatedChildIds = updatedIds, archivedChildIds = archivedIds, restoredChildIds = restoredIds)
    }

    override suspend fun recordAudit(record: AuditLogRecord) = database { connection.insertAudit(record); Unit }

    private fun PreparedStatement.bindIntegration(record: ExternalIntegrationRecord) {
        setObject(1, record.id); setObject(2, record.centerId); setString(3, record.type.name); setString(4, record.name); setString(5, record.baseUrl)
        setString(6, record.encryptedCredential); setString(7, record.credentialNonce); setString(8, record.credentialKeyVersion); setString(9, record.status.name)
        setInstant(10, record.lastConnectionCheckAt); setInstant(11, record.lastSuccessfulSyncAt); setInstant(12, record.createdAt); setInstant(13, record.updatedAt)
    }
    private fun Connection.lockIntegration(id: UUID, centerId: UUID) = prepareStatement("SELECT id FROM external_integrations WHERE id = ? AND center_id = ? FOR UPDATE").use { statement ->
        statement.setObject(1, id); statement.setObject(2, centerId); statement.executeQuery().use { result -> check(result.next()) }
    }
    private fun Connection.linksForUpdate(integrationId: UUID, externalIds: List<String>): List<ExternalEntityLinkRecord> {
        if (externalIds.isEmpty()) return emptyList()
        val placeholders = externalIds.joinToString(",") { "?" }
        return prepareStatement("SELECT * FROM external_entity_links WHERE integration_id = ? AND entity_type = 'CHILD' AND external_id IN ($placeholders) FOR UPDATE").use { statement ->
            statement.setObject(1, integrationId); externalIds.forEachIndexed { index, id -> statement.setString(index + 2, id) }; statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toLink()) } }
        }
    }
    private fun Connection.linksForIntegrationForUpdate(integrationId: UUID): List<ExternalEntityLinkRecord> = prepareStatement("SELECT * FROM external_entity_links WHERE integration_id = ? AND entity_type = 'CHILD' FOR UPDATE").use { statement ->
        statement.setObject(1, integrationId); statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toLink()) } }
    }
    private fun Connection.findIntegration(sql: String, bind: PreparedStatement.() -> Unit): ExternalIntegrationRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toIntegration() else null } }
    private fun Connection.findChildForUpdate(centerId: UUID, id: UUID): ChildRecord? = prepareStatement("SELECT * FROM children WHERE center_id = ? AND id = ? FOR UPDATE").use { statement ->
        statement.setObject(1, centerId); statement.setObject(2, id); statement.executeQuery().use { if (it.next()) it.toChild() else null }
    }
    private fun Connection.insertChild(child: ChildRecord) = prepareStatement("INSERT INTO children (id, center_id, first_name, last_name, birth_date, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
        statement.setObject(1, child.id); statement.setObject(2, child.centerId); statement.setString(3, child.firstName); statement.setString(4, child.lastName); statement.setObject(5, child.birthDate); statement.setString(6, child.status.name); statement.setInstant(7, child.createdAt); statement.setInstant(8, child.updatedAt); statement.executeUpdate()
    }
    private fun Connection.updateChildFromCrm(child: ChildRecord) = prepareStatement("UPDATE children SET first_name = ?, last_name = ?, birth_date = ?, status = ?, updated_at = ? WHERE id = ? AND center_id = ?").use { statement ->
        statement.setString(1, child.firstName); statement.setString(2, child.lastName); statement.setObject(3, child.birthDate); statement.setString(4, child.status.name); statement.setInstant(5, child.updatedAt); statement.setObject(6, child.id); statement.setObject(7, child.centerId); check(statement.executeUpdate() == 1)
    }
    private fun Connection.insertLink(link: ExternalEntityLinkRecord) = prepareStatement("INSERT INTO external_entity_links (id, center_id, integration_id, entity_type, external_id, local_entity_id, last_synced_at, external_updated_at, crm_archived_local, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
        statement.setObject(1, link.id); statement.setObject(2, link.centerId); statement.setObject(3, link.integrationId); statement.setString(4, link.entityType.name); statement.setString(5, link.externalId); statement.setObject(6, link.localEntityId); statement.setInstant(7, link.lastSyncedAt); statement.setInstant(8, link.externalUpdatedAt); statement.setBoolean(9, link.crmArchivedLocal); statement.setInstant(10, link.createdAt); statement.setInstant(11, link.updatedAt); statement.executeUpdate()
    }
    private fun Connection.updateLink(link: ExternalEntityLinkRecord) = prepareStatement("UPDATE external_entity_links SET last_synced_at = ?, external_updated_at = ?, crm_archived_local = ?, updated_at = ? WHERE id = ? AND center_id = ? AND integration_id = ?").use { statement ->
        statement.setInstant(1, link.lastSyncedAt); statement.setInstant(2, link.externalUpdatedAt); statement.setBoolean(3, link.crmArchivedLocal); statement.setInstant(4, link.updatedAt); statement.setObject(5, link.id); statement.setObject(6, link.centerId); statement.setObject(7, link.integrationId); check(statement.executeUpdate() == 1)
    }
    private fun Connection.updateSuccessfulSync(integrationId: UUID, centerId: UUID, now: Instant) = prepareStatement("UPDATE external_integrations SET last_successful_sync_at = ?, updated_at = ? WHERE id = ? AND center_id = ?").use { statement -> statement.setInstant(1, now); statement.setInstant(2, now); statement.setObject(3, integrationId); statement.setObject(4, centerId); check(statement.executeUpdate() == 1) }
    private fun Connection.insertAudit(record: AuditLogRecord) = prepareStatement("INSERT INTO audit_logs (id, center_id, actor_type, actor_id, action, entity_type, entity_id, metadata, ip_address, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)").use { statement ->
        statement.setObject(1, record.id); statement.setObject(2, record.centerId); statement.setString(3, record.actorType.name); statement.setObject(4, record.actorId); statement.setString(5, record.action); statement.setString(6, record.entityType); statement.setObject(7, record.entityId); statement.setString(8, record.metadata); statement.setString(9, record.ipAddress); statement.setInstant(10, record.createdAt); statement.executeUpdate()
    }
    private fun PreparedStatement.setInstant(index: Int, value: Instant?) = setTimestamp(index, value?.let(java.sql.Timestamp::from))
    private fun ResultSet.toIntegration() = ExternalIntegrationRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), ExternalIntegrationType.valueOf(getString("type")), getString("name"), getString("base_url"), getString("encrypted_credential"), getString("credential_nonce"), getString("credential_key_version"), ExternalIntegrationStatus.valueOf(getString("status")), getTimestamp("last_connection_check_at")?.toInstant(), getTimestamp("last_successful_sync_at")?.toInstant(), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toLink() = ExternalEntityLinkRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getObject("integration_id", UUID::class.java), ExternalEntityType.valueOf(getString("entity_type")), getString("external_id"), getObject("local_entity_id", UUID::class.java), getTimestamp("last_synced_at")?.toInstant(), getTimestamp("external_updated_at")?.toInstant(), getBoolean("crm_archived_local"), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toChild() = ChildRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getString("first_name"), getString("last_name"), getObject("birth_date", java.time.LocalDate::class.java), ChildStatus.valueOf(getString("status")), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private val connection: Connection get() = (TransactionManager.current().connection as JdbcConnectionImpl).connection
    private suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
}
