package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ExternalIntegrationStatus
import kz.oyla.server.model.ExternalIntegrationType

@Serializable data class CrmIntegrationDto(
    val id: String,
    val type: ExternalIntegrationType = ExternalIntegrationType.CUSTOM_CRM,
    val name: String,
    val baseUrl: String,
    val status: ExternalIntegrationStatus,
    val hasCredential: Boolean,
    val lastConnectionCheckAt: String? = null,
    val lastSuccessfulSyncAt: String? = null
)

@Serializable data class SaveCrmIntegrationRequest(
    val name: String? = null,
    val baseUrl: String,
    val apiKey: String
)

/** An empty apiKey preserves the saved encrypted credential during PATCH. */
@Serializable data class UpdateCrmIntegrationRequest(
    val name: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null
)

@Serializable data class CrmConnectionTestRequest(val baseUrl: String? = null, val apiKey: String? = null)

@Serializable enum class CrmConnectionTestStatus { SUCCESS, AUTH_FAILED, UNREACHABLE, INVALID_RESPONSE, TIMEOUT, TLS_ERROR }
@Serializable data class CrmConnectionTestResponse(val status: CrmConnectionTestStatus)

@Serializable data class ExternalCrmChildDto(
    val id: String,
    val firstName: String,
    val lastName: String,
    val birthDate: String,
    val status: String,
    val updatedAt: String
)

/** The health payload deliberately has no assumed provider fields. */
data class ExternalCrmHealthResponse(val isJsonObject: Boolean = true)

@Serializable enum class CrmChildImportState { NOT_IMPORTED, IMPORTED, UPDATE_AVAILABLE, ARCHIVED_EXTERNAL }
@Serializable data class CrmChildPreviewDto(
    val externalId: String,
    val firstName: String,
    val lastName: String,
    val birthDate: String,
    val status: String,
    val externalUpdatedAt: String,
    val importState: CrmChildImportState,
    val localChildId: String? = null
)
@Serializable data class CrmChildrenPreviewResponse(val children: List<CrmChildPreviewDto>)
@Serializable data class ImportCrmChildrenRequest(val externalIds: List<String>)
@Serializable data class ImportCrmChildrenResponse(val imported: Int, val updated: Int, val skipped: Int)
@Serializable data class CrmSyncResponse(
    val checked: Int,
    val updated: Int,
    val archived: Int,
    val restored: Int,
    val unchanged: Int,
    val errors: Int
)

@Serializable data class ChildDataSourceDto(
    val provider: String,
    val integrationStatus: ExternalIntegrationStatus,
    val lastSyncedAt: String? = null,
    val crmManaged: Boolean
)
