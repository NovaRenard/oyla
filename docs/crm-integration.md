# CRM integration

Oyla supports a first, deliberately narrow integration: **manual, one-way CRM → Oyla import and sync of children**. Oyla makes all outbound requests from `oyla-server`; neither the web browser nor Android receives the CRM URL or key.

## Boundaries and ownership

`ExternalIntegration` is tenant-scoped and currently supports `CUSTOM_CRM`. `ExternalEntityLink` connects a CRM identifier to Oyla's own UUID for a `CHILD`; an external ID is never used as `children.id`. The schema permits historical and future multiple integrations, while the current UI uses one CRM connection per center.

CRM is the source of truth for imported `firstName`, `lastName`, `birthDate`, and active/archive status. Those fields are read-only in Oyla while the CRM connection is active. Oyla remains the source of truth for its child UUID, lessons, skills, attempts, progress, mastery, and all Oyla-only configuration/history. A sync updates only CRM-owned child fields and link timestamps; it never deletes or rewrites learning data.

## Credential handling

The API key is accepted only by Oyla's authenticated settings endpoint and is never returned by API responses. It is not written to browser storage, audit metadata, logs, Android, or plaintext PostgreSQL columns. The database holds AES-256-GCM ciphertext, a random nonce, and a key version. The server decrypts it only immediately before an authenticated server-to-server request.

Production requires `INTEGRATION_CREDENTIAL_ENCRYPTION_KEY`, a base64-encoded 32-byte AES key independent of `JWT_SECRET`, `OYLA_SECRET_PEPPER`, and `POSTGRES_PASSWORD`. Startup fails if it is absent or malformed. Use a real secret manager for the key. Database backups contain encrypted envelopes and `ExternalEntityLink` provenance; recovering a live integration also requires restoring the same encryption key separately. Do not put this secret in a backup archive.

## CRM contract

Oyla calls these fixed paths below the validated base URL with `Authorization: Bearer <API_KEY>` and `Accept: application/json`:

```http
GET /api/integrations/oyla/v1/health
GET /api/integrations/oyla/v1/children
```

The health payload only needs to be a JSON object; Oyla does not assume center IDs or extra fields. The children endpoint currently returns a JSON array with exactly the provider fields used by the integration:

```json
{
  "id": "external-child-uuid",
  "firstName": "Алихан",
  "lastName": "Сарсенов",
  "birthDate": "2019-04-16",
  "status": "ACTIVE",
  "updatedAt": "2026-08-12T07:30:00Z"
}
```

`ACTIVE` maps to Oyla `ACTIVE`; `ARCHIVED` and `INACTIVE` map explicitly to Oyla `ARCHIVED`. Any unknown status, duplicate external ID, malformed date/timestamp, non-array children response, or invalid child rejects the entire fetched response before database mutation. There is no pagination contract yet, so Oyla does not invent one. A future paginated client must preserve the same complete-validation-before-write boundary.

## Outbound security

The integration client accepts only HTTPS in production. It rejects non-HTTP schemes, userinfo, fragments, queries, malformed/oversized URLs, localhost, loopback, link-local, private/internal, multicast, and reserved addresses. DNS is resolved and checked when a URL is saved and again before every outbound attempt. Redirects are disabled entirely, preventing a public CRM from redirecting a request into an internal network. Timeouts, a 1 MiB response limit, JSON content-type enforcement, and at most two GET retries bound outbound work. A non-production `INTEGRATION_ALLOW_UNSAFE_LOCALHOST=true` override exists solely for local mock tests; it is rejected in production.

Outbound observability is limited to non-secret operational data such as integration/center ID, endpoint category, elapsed duration, HTTP status, and result counts. Authorization headers, keys, and raw CRM response bodies are never logged.

## User flow and API

Only `OWNER` and `ADMIN` may configure, test, import, synchronize, or disconnect CRM. `METHODIST` and `SPECIALIST` cannot access integration settings. Tenant context comes from the existing web JWT active center.

- `GET /api/v1/integrations/crm` returns safe settings only (`hasCredential`, never a key).
- `POST` and `PATCH /api/v1/integrations/crm` test the candidate URL/key server-side before persisting it. An empty PATCH `apiKey` retains the existing key. Failed rotation tests leave the known working encrypted credential untouched.
- `POST /api/v1/integrations/crm/test` returns a safe category: `SUCCESS`, `AUTH_FAILED`, `UNREACHABLE`, `INVALID_RESPONSE`, `TIMEOUT`, or `TLS_ERROR`.
- `GET /api/v1/integrations/crm/children` is a live preview only. It does not persist CRM records and annotates every child as `NOT_IMPORTED`, `IMPORTED`, `UPDATE_AVAILABLE`, or `ARCHIVED_EXTERNAL`.
- `POST /api/v1/integrations/crm/import-children` accepts only selected `externalIds`; Oyla fetches CRM again and imports the current server-provided records atomically. Repeating an import uses the link and cannot create a duplicate child.
- `POST /api/v1/integrations/crm/sync` fetches CRM and affects linked children only. Newly discovered CRM children are never auto-imported.
- `DELETE /api/v1/integrations/crm` clears the encrypted credential and disables the integration, retaining children, links, lessons, skills, and history. Disconnected imported children become locally editable; the UI retains CRM provenance with `CRM отключена`.

Manual sync does not delete a child missing from CRM. A CRM archive archives the local child while retaining all history. Oyla records whether an archive was CRM-driven; only that kind is automatically restored if CRM later returns `ACTIVE`. A manual Oyla archive/restore clears that marker so sync cannot silently reverse an Oyla decision.

Test, preview, and sync calls are rate limited per center. Audit actions include connection tests, integration create/update/disable, child import/sync/archive/restore, and sync completion/failure. Audit metadata contains counts/result codes only—never credentials.

## CRM-side smoke test checklist

For a production smoke test, the CRM owner must expose a publicly reachable HTTPS base URL from the Oyla server, provision a scoped bearer key, and implement the two paths above. Verify health with the key, return a JSON children array containing the stated fields only, then change a child's name, archive it, and restore it to confirm Oyla's manual sync behavior. Do not expose CRM through the browser, add an Oyla center ID as a trust signal, or return Oyla learning/history data.
