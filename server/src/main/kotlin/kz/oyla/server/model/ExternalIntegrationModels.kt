package kz.oyla.server.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ExternalIntegrationType { CUSTOM_CRM }
enum class ExternalIntegrationStatus { ACTIVE, DISABLED, ERROR }
enum class ExternalEntityType { CHILD }

data class ExternalIntegrationRecord(
    val id: UUID,
    val centerId: UUID,
    val type: ExternalIntegrationType,
    val name: String,
    val baseUrl: String,
    val encryptedCredential: String?,
    val credentialNonce: String?,
    val credentialKeyVersion: String?,
    val status: ExternalIntegrationStatus,
    val lastConnectionCheckAt: Instant?,
    val lastSuccessfulSyncAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ExternalEntityLinkRecord(
    val id: UUID,
    val centerId: UUID,
    val integrationId: UUID,
    val entityType: ExternalEntityType,
    val externalId: String,
    val localEntityId: UUID,
    val lastSyncedAt: Instant?,
    val externalUpdatedAt: Instant?,
    val crmArchivedLocal: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** Validated, provider-owned child data. This never contains browser supplied data. */
data class ExternalCrmChild(
    val externalId: String,
    val firstName: String,
    val lastName: String,
    val birthDate: LocalDate,
    val status: ChildStatus,
    val externalUpdatedAt: Instant
)

data class ImportedChildSource(
    val integrationId: UUID,
    val integrationName: String,
    val integrationStatus: ExternalIntegrationStatus,
    val lastSyncedAt: Instant?
)
