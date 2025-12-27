# JIT Database Access & Recording Architecture

## Overview

This document describes a **Just-In-Time (JIT) database access system** with **session recording** capabilities. The architecture enables time-limited, audited database access while keeping database clients (IntelliJ, DBeaver, psql, cqlsh, mongosh) completely unmodified.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ARCHITECTURE OVERVIEW                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌──────────┐  HTTPS  ┌──────────────┐  SCIM/HTTPS  ┌──────────────┐       │
│   │  jit-ui  │────────▶│  jit-server  │─────────────▶│  Directory   │       │
│   │  (Web)   │         │ (Control)    │ grant/revoke │  / IAM       │       │
│   └──────────┘         └──────▲───────┘              └──────────────┘       │
│                               │                                              │
│                               │ HTTPS (authorize, session lifecycle)         │
│                               │                                              │
│   ┌──────────┐  TLS    ┌──────┴───────┐  TLS+GSSAPI  ┌──────────────┐       │
│   │  pamjit  │────────▶│  jit-proxy   │─────────────▶│   Database   │       │
│   │ (Agent)  │ prelude │ (Data Plane) │   record     │ (CockroachDB │       │
│   └────▲─────┘         └──────┬───────┘              │  Cassandra   │       │
│        │                      │                       │  MongoDB)    │       │
│        │ localhost            │ HTTPS                 └──────────────┘       │
│   ┌────┴─────┐                ▼                                              │
│   │  Client  │         ┌──────────────┐                                     │
│   └──────────┘         │ cred-service │                                     │
│                        │ (Credentials)│                                     │
│   IntelliJ / DBeaver   └──────────────┘                                     │
│   psql / cqlsh / mongosh                                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Glossary

| Acronym | Meaning |
|---------|---------|
| **JIT** | Just-In-Time (time-limited access) |
| **JWT** | JSON Web Token |
| **TLS** | Transport Layer Security |
| **SCIM** | System for Cross-domain Identity Management |
| **GSSAPI** | Generic Security Services API (Kerberos mechanism) |
| **TGT** | Ticket-Granting Ticket (Kerberos credential) |
| **RBAC** | Role-Based Access Control |

---

## Design Goals

1. **Zero client modification** - Database clients remain unchanged
2. **Centralized authorization** - jit-server is the single source of truth
3. **Audit trail** - Full query logging for Postgres/CockroachDB; command-level audit for Cassandra/MongoDB per engine maturity
4. **Time-bounded access** - Grants expire automatically via SCIM
5. **Multi-protocol support** - Postgres, Cassandra, MongoDB

---

## Components

### jit-ui (Web Interface)

**Role**: User-facing portal for access requests

- Collects asset selection, duration, ticket, roles
- Calls jit-server HTTPS endpoints
- Displays approval results and grant identifiers

> **Note**: Host/port is supplied by the user to pamjit (or read from local config). jit-server enforces **network policy**: blocks loopback (`127.0.0.0/8`, `::1`), link-local (`169.254.0.0/16`, `fe80::/10`), unspecified, and multicast; enforces allowed port sets per `db_type`; optionally validates per-asset host allowlists if configured. Private range policy (`10/8`, `172.16/12`, `192.168/16`) is deployment-specific.

### jit-server (Control Plane)

**Role**: Authorization hub and state machine

- Validates JWT identity
- Validates entitlements and ticket rules
- Connects to Directory / IAM via SCIM (HTTPS) to grant/revoke roles
- Stores authoritative approval state in CockroachDB
- Computes `bundle_id` for session attribution

### cred-service (Credential Provider)

**Role**: Runtime credential distribution

- Provides Kerberos TGT/credential cache for database auth
- Returns tokens/credentials based on database type
- **Only called by jit-proxy** - no other component accesses cred-service directly
- jit-proxy authenticates to cred-service via service JWT

**Credential response options**:
- Path to a credential cache file mounted/accessible on jit-proxy, or
- Byte blob + format + TTL; jit-proxy writes to temp ccache file

> One principal per TGT is sufficient; database roles are managed via SCIM on the Directory/IAM side.

### jit-proxy (Data Plane)

**Role**: Database proxy with recording

- Accepts TLS connections from pamjit
- Performs lightweight prelude validation (framing, JSON schema, size limits, ts window)
- Forwards to jit-server for authoritative JWT validation + anti-replay + authorization
- Fetches credentials from cred-service
- Connects to backend database (TLS + GSSAPI)
- Records all queries/commands to JSONL
- Reports session lifecycle to jit-server
- **Stateful per connection**: prepared statement maps, cancel key mapping, recording file handles, protocol state

> **Auth pattern**: jit-proxy authenticates to jit-server using service JWT, passing the user's JWT via `X-End-User-JWT` header. jit-proxy does not interpret the user JWT beyond basic size limits.

### pamjit (Local Agent)

**Role**: Client-side forwarder (tsh-like)

- Runs localhost listener for database clients
- Opens TLS connection to jit-proxy
- Sends authentication prelude
- Forwards bytes bidirectionally (protocol-agnostic)

### Protocol: Client ↔ pamjit

- Client connects to `127.0.0.1:<local_port>` (plain TCP, no TLS)
- pamjit does **not** parse database protocol; it only forwards bytes after jit-proxy approves
- While authorization is pending, pamjit holds the client socket open but does not forward bytes until it receives OK from jit-proxy
- If authorization fails, pamjit closes the client connection immediately

---

## Identifiers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          IDENTIFIER RELATIONSHIPS                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  User Request                    DB Connection                               │
│       │                               │                                      │
│       ▼                               ▼                                      │
│  ┌─────────────┐              ┌──────────────┐                              │
│  │ activityUID │              │ db_session_id│                              │
│  │ (per grant) │              │ (per TCP conn)│                             │
│  └──────┬──────┘              └──────┬───────┘                              │
│         │                            │                                       │
│         │    ┌───────────────────────┘                                       │
│         │    │                                                               │
│         ▼    ▼                                                               │
│  ┌─────────────────────────────────────┐                                     │
│  │             bundle_id               │                                     │
│  │  SHA256(sorted(active_activityUIDs))│                                     │
│  │                                     │                                     │
│  │  Labels recordings with effective   │                                     │
│  │  privileges at session start        │                                     │
│  └─────────────────────────────────────┘                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Identifier | Created By | Purpose |
|------------|------------|---------|
| `activityUID` | jit-server | Tracks individual grant request/approval |
| `db_session_id` | jit-proxy | Identifies single TCP database connection |
| `bundle_id` | jit-server | Labels session with set of active grants (attribution/reporting) |
| `session_token` | jit-server | Single-use token binding authorize → session lifecycle |

**`db_session_id` generation**: jit-proxy generates `db_session_id` (UUID) immediately after successful prelude parse, before calling `/authorize`. This ID is included in the authorize request and returned in CONNECT_DECISION.

### Why bundle_id?

A user may have multiple concurrent grants for the same asset (multiple roles approved at different times). At DB session start, effective privileges are the **union** of all active grants. `bundle_id` labels that union for accurate post-activity attribution.

> **Important**: `bundle_id` is computed from the active `activityUID` set at authorization time. It is **not a credential** and is not used for authorization—it is purely an attribution label for reporting and compliance.

### bundle_id vs session_token

| Token | Purpose | Trust Level |
|-------|---------|-------------|
| `session_token` | **Binding**: links `/sessions/start` to a specific authorize decision | Authoritative; single-use, short TTL (60s), validated by jit-server |
| `bundle_id` | **Attribution**: labels session with effective grants for compliance reporting | Informational; echoed for consistency, not validated |

> `bundle_id` is an **attribution label only**. jit-server may store it but does not rely on it for authorization decisions; authoritative binding is via `session_token`. `session_token` prevents a compromised proxy from fabricating session reports.

---

## End-to-End Workflow

### Phase 1: Access Request (Control Plane)

```
┌──────────┐     ┌──────────┐     ┌─────────────┐     ┌────────────┐
│   User   │     │  jit-ui  │     │ jit-server  │     │ Directory  │
│          │     │          │     │             │     │   / IAM    │
└────┬─────┘     └────┬─────┘     └──────┬──────┘     └─────┬──────┘
     │                │                   │                  │
     │ 1. Request     │                   │                  │
     │    Access      │                   │                  │
     ├───────────────▶│                   │                  │
     │                │                   │                  │
     │                │ 2. POST /request  │                  │
     │                ├──────────────────▶│                  │
     │                │                   │                  │
     │                │                   │ 3. Validate JWT  │
     │                │                   │    Entitlements  │
     │                │                   │    Ticket Rules  │
     │                │                   │                  │
     │                │                   │ 4. SCIM Grant    │
     │                │                   ├─────────────────▶│
     │                │                   │                  │
     │                │                   │◀─────────────────┤
     │                │                   │                  │
     │                │ 5. activityUID(s) │                  │
     │                │◀──────────────────┤                  │
     │                │                   │                  │
     │ 6. Grant       │                   │                  │
     │    Status      │                   │                  │
     │◀───────────────┤                   │                  │
     │                │                   │                  │
```

### Phase 2: Database Connection (Data Plane)

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌───────────┐  ┌──────────┐
│  Client  │  │  pamjit  │  │ jit-proxy│  │jit-server │  │cred-service│  │ Database │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬─────┘  └─────┬─────┘  └────┬─────┘
     │             │             │              │              │              │
     │             │ 1. Start    │              │              │              │
     │             │    Listener │              │              │              │
     │             │ (localhost) │              │              │              │
     │             │             │              │              │              │
     │ 2. Connect  │             │              │              │              │
     ├────────────▶│             │              │              │              │
     │             │             │              │              │              │
     │             │ 3. TLS      │              │              │              │
     │             ├────────────▶│              │              │              │
     │             │             │              │              │              │
     │             │ 4. Prelude  │              │              │              │
     │             │ (JWT,asset, │              │              │              │
     │             │  ts,nonce)  │              │              │              │
     │             ├────────────▶│              │              │              │
     │             │             │              │              │              │
     │             │             │ 5. Authorize │              │              │
     │             │             ├─────────────▶│              │              │
     │             │             │              │              │              │
     │             │             │ 6. bundle_id │              │              │
     │             │             │◀─────────────┤              │              │
     │             │             │              │              │              │
     │             │             │ 7. Get Creds │              │              │
     │             │             ├─────────────────────────────▶              │
     │             │             │              │              │              │
     │             │             │ 8. TGT/Cache │              │              │
     │             │             │◀─────────────────────────────┤              │
     │             │             │              │              │              │
     │             │             │ 9. TLS + GSSAPI Connect     │              │
     │             │             ├─────────────────────────────────────────────▶
     │             │             │              │              │              │
     │             │ 10. ACK     │              │              │              │
     │             │◀────────────┤              │              │              │
     │             │             │              │              │              │
     │ 11. Ready   │             │              │              │              │
     │◀────────────┤             │              │              │              │
     │             │             │              │              │              │
     │ 12. SQL/CQL │ forward     │ forward + record            │              │
     ├────────────▶├────────────▶├─────────────────────────────────────────────▶
     │             │             │              │              │              │
     │◀────────────┼─────────────┼◀─────────────────────────────────────────────┤
     │  Results    │             │              │              │              │
```

### Phase 3: Session End

```
┌──────────┐  ┌──────────┐  ┌───────────┐  ┌─────────────────────┐
│  Client  │  │ jit-proxy│  │jit-server │  │post-activity-review │
└────┬─────┘  └────┬─────┘  └─────┬─────┘  └──────────┬──────────┘
     │             │              │                   │
     │ Disconnect  │              │                   │
     ├────────────▶│              │                   │
     │             │              │                   │
     │             │ Close        │                   │
     │             │ Recording    │                   │
     │             │              │                   │
     │             │ Session End  │                   │
     │             │ Summary      │                   │
     │             ├─────────────▶│                   │
     │             │              │                   │
     │             │              │ Forward to        │
     │             │              │ Review Server     │
     │             │              ├──────────────────▶│
     │             │              │                   │
```

---

## Prelude Protocol

### Transport

- TLS over TCP (server-auth only). pamjit does not present a client certificate; user identity is carried by the prelude JWT.
- jit-proxy requires TLS from byte 0
- Prelude is sent **once** per pamjit→jit-proxy TCP connection, before any DB bytes
- After authorization succeeds, jit-proxy switches to streaming mode (DB wire protocol only)
- **If prelude validation fails, jit-proxy closes immediately and never forwards any client DB bytes**

### Message Format

```
┌─────────────────────────────────────────────────────────────────┐
│                      PRELUDE MESSAGE                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┬────────────────────────────────────────────┐  │
│  │ uint32 len  │              JSON payload                   │  │
│  │ (big-endian)│              (UTF-8)                        │  │
│  └─────────────┴────────────────────────────────────────────┘  │
│                                                                 │
│  Payload Fields:                                                │
│  ┌────────────────────────────────────────────────────────┐    │
│  │ {                                                       │    │
│  │   "version": 1,                                         │    │
│  │   "jwt": "<user-jwt-token>",                           │    │
│  │   "asset_uid": "asset-uuid",                           │    │
│  │   "target_host": "db.example.com",                     │    │
│  │   "target_port": 26257,                                │    │
│  │   "ts_epoch_ms": 1730000000000,                        │    │
│  │   "nonce_b64": "random-base64-nonce"                   │    │
│  │ }                                                       │    │
│  └────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

**Wire format rules**:
- Length prefix: big-endian uint32
- Max payload size: 64 KB
- Encoding: UTF-8
- On parse failure: close connection immediately

**JWT forwarding**: jit-proxy forwards `prelude.jwt` **verbatim** to jit-server in `X-End-User-JWT` header. jit-proxy does not re-mint, transform, or interpret the JWT beyond size limits.

### Identity Trust Boundaries

| Identity Type | Source | Validated By |
|---------------|--------|--------------|
| **End-user** | `X-End-User-JWT` header | jit-server only |
| **Service** | `Authorization: Bearer <service_jwt>` | jit-server, cred-service |
| **Workstation** | None (no client cert) | N/A |

- jit-proxy treats end-user JWT as **opaque** and forwards it verbatim
- pamjit provides no workstation identity beyond TLS server verification + end-user JWT
- All authorization decisions derive from end-user identity validated by jit-server

### JWT & Token Redaction Rules

Both `X-End-User-JWT` and `Authorization: Bearer <service_jwt>` headers contain sensitive credentials and **must be treated as secret**:

| Context | Rule |
|---------|------|
| HTTP access logs | **Redact** (omit or mask entirely) |
| Application logs | **Redact** |
| Metrics/traces | **Never emit** |
| Debugging | Allowed: hashed fingerprint only, e.g., `sha256(jwt)[:16]` |

> **Rationale**: JWT leakage enables session hijacking (user JWT) or service impersonation (service JWT). Fingerprints allow correlation without exposing the token.

### Anti-Replay Protection

| Field | Purpose | Validation |
|-------|---------|------------|
| `ts_epoch_ms` | Timestamp freshness | jit-proxy: sanity check; **jit-server: authoritative** |
| `nonce_b64` | Unique per request | **jit-server** shared TTL store (2-5 min) |

**Validation split**:
- **jit-proxy**: lightweight ts sanity (drop obviously stale/future timestamps, e.g., >120s skew); best-effort local nonce cache using `sha256(raw_jwt + asset_uid + nonce)` as key
- **jit-server**: authoritative ts window check (rejects `ts_epoch_ms` outside ±120s even if nonce is new) + authoritative nonce uniqueness via shared TTL store (**recommended: Redis**). CockroachDB can be used with unique constraint + TTL cleanup, but is not preferred due to write load/latency.

> **Window consistency**: Both jit-proxy sanity check and jit-server authoritative check use ±120s. Total prelude→ready timeout (30s) is well under this window.

**Nonce cache key** (jit-server): `hash(jwt_sub + asset_uid + nonce)` — computed after JWT validation, prevents replay across users/assets.

### Nonce Format

- **Generation**: MUST use cryptographically secure random number generator (CSPRNG)
- **Encoding**: base64url (RFC 4648 URL-safe, no padding required)
- **Decoded length**: 16–32 bytes required
- **Validation**: reject on decode failure or length mismatch

### CONNECT_DECISION Frame (jit-proxy → pamjit)

After prelude processing, jit-proxy sends **exactly one** decision frame on the **same TLS stream**, immediately after prelude and before any database bytes are forwarded. This allows pamjit to distinguish authorization outcomes from connection failures.

**Wire format**: Same as prelude (big-endian uint32 length + UTF-8 JSON payload).

```json
{
  "allowed": true,
  "db_session_id": "uuid",
  "bundle_id": "sha256-hash",
  "bundle_expires_at": "2025-12-30T12:00:00Z"
}
```

**Denied/Error response**:

```json
{
  "allowed": false,
  "reason": "no_active_grants"
}
```

**Reason enum** (when `allowed: false`):
- `invalid_prelude` — prelude parse/validation error
- `replay_detected` — ts/nonce replay rejected
- `authorize_timeout` — jit-server call timed out
- `no_active_grants` — user has no valid grants for asset
- `authorize_denied` — jit-server rejected (policy, entitlement)
- `cred_failed` — cred-service call failed
- `db_connect_failed` — backend DB connection failed
- `db_auth_failed` — backend DB auth (GSSAPI/SASL) failed
- `server_busy` — jit-proxy overloaded (backpressure)

> **Error priority**: If multiple failures occur, return the **earliest failure in the pipeline** (listed in order above). This ensures deterministic, debuggable error responses.

> **Must-send rule**: jit-proxy MUST attempt to send CONNECT_DECISION for **all failures after TLS is established and prelude length is readable**. Only hard exceptions (TLS handshake failure, OOM, process crash) may result in no frame. This reduces "mystery disconnects" for clients.

> **Protocol note**: pamjit MUST wait for this frame before forwarding any client bytes. If the socket closes before receiving CONNECT_DECISION, pamjit should treat it as an internal error.

---

## jit-server API

All endpoints use **POST** (authorization decisions with request context).

### Service JWT Requirements

jit-proxy authenticates to jit-server using a service JWT with:
- **Short TTL** (e.g., 5-15 minutes, auto-refreshed)
- **Audience** (`aud`) bound to jit-server
- **Scopes/claims** restricting access to specific endpoints (`db:authorize`, `db:sessions`)
- **Rate limits** enforced per service identity

### POST `/api/v1/db/connect/authorize`

Called by jit-proxy to authorize a new connection.

**Headers**:
```
Authorization: Bearer <service_jwt>       # jit-proxy service identity
X-End-User-JWT: <user_jwt>                # end-user identity (validated by jit-server)
```

**Body**:
```json
{
  "db_session_id": "uuid",
  "asset_uid": "asset-uuid",
  "target_host": "db.example.com",
  "target_port": 26257,
  "ts_epoch_ms": 1730000000000,
  "nonce_b64": "..."
}
```

> **Note**: User identity is derived from `X-End-User-JWT`, not from the request body. jit-server validates the end-user JWT and extracts claims. `db_session_id` is generated by jit-proxy before this call.

> **Target validation**: jit-server validates that `(asset_uid, target_host, target_port)` matches asset metadata and allowed port sets for the returned `db_type`. Target enforcement is server-side, not implied by jit-proxy.

**Response (Success)**

```json
{
  "allowed": true,
  "bundle_id": "sha256-hash-of-sorted-activity-uids",
  "bundle_expires_at": "2025-12-30T12:00:00Z",
  "db_type": "cockroachdb",
  "session_token": "opaque-single-use-token"
}
```

> **`session_token`**: Opaque, single-use token (short TTL, e.g., 60s). Required on `/sessions/start` to bind session lifecycle to authorize decision. Prevents a compromised proxy from minting fake sessions.

**Response (Denied)**

```json
{
  "allowed": false,
  "reason": "no_active_grants"
}
```

### POST `/api/v1/db/sessions/start`

Called by jit-proxy when DB connection is established.

**Headers**: `Authorization: Bearer <service_jwt>` only (no end-user JWT required; session is bound by `session_token`).

**Body**:
```json
{
  "session_token": "opaque-single-use-token",
  "db_session_id": "uuid",
  "bundle_id": "sha256-hash",
  "asset_uid": "asset-uuid",
  "db_type": "cockroachdb",
  "target_host": "db.example.com",
  "target_port": 26257,
  "proxy_instance_id": "proxy-pod-xyz",
  "client_addr": "10.1.2.3:54321",
  "start_time": "2025-12-30T10:00:00Z"
}
```

> **`db_type` trust**: jit-proxy MUST set `db_type` from the `/authorize` response, not from prelude or local config.

**Response**: `200 OK` with empty body, or `4xx` on validation failure (invalid/expired `session_token`).

### POST `/api/v1/db/sessions/end`

Called by jit-proxy when DB session terminates (includes summary).

**Headers**: `Authorization: Bearer <service_jwt>` only.

**Body**:
```json
{
  "db_session_id": "uuid",
  "bundle_id": "sha256-hash",
  "bundle_expires_at": "2025-12-30T12:00:00Z",
  "start_time": "2025-12-30T10:00:00Z",
  "end_time": "2025-12-30T10:05:00Z",
  "expired_while_connected": false,
  "status": "COMPLETED",
  "termination_reason": "CLIENT_CLOSE",
  "query_count": 42,
  "error_count": 0,
  "bytes_up": 12345,
  "bytes_down": 67890,
  "recording_ref": "s3://bucket/recordings/uuid.jsonl",
  "recording_sha256": "abc123..."
}
```

**Enums**:

| Field | Values |
|-------|--------|
| `status` | `COMPLETED`, `ABORTED`, `FAILED` |
| `termination_reason` | `CLIENT_CLOSE`, `AUTHORIZE_DENY`, `AUTHORIZE_TIMEOUT`, `CRED_FAILED`, `DB_AUTH_FAILED`, `DB_CONN_FAILED`, `PROTOCOL_ERROR`, `INTERNAL_ERROR`, `SERVER_BUSY` |

> **Note**: `ABORTED` = session started but ended abnormally (DB error, timeout). `FAILED` = session never fully established (auth/connect failure).

### Data Model (jit-server)

**`authorize_decisions`** (TTL: 60s)
| Column | Type | Description |
|--------|------|-------------|
| `session_token` | PK | Opaque single-use token |
| `jwt_sub` | string | End-user identity |
| `asset_uid` | UUID | Target asset |
| `bundle_id` | string | Attribution label |
| `bundle_expires_at` | timestamp | Grant expiration |
| `db_type` | enum | cockroachdb, cassandra, mongodb |
| `target_host` | string | Validated target |
| `target_port` | int | Validated port |
| `issued_at` | timestamp | Token issue time |

**`sessions`** (keyed by `db_session_id`)
| Column | Type | Description |
|--------|------|-------------|
| `db_session_id` | PK | UUID from jit-proxy |
| `bundle_id` | string | Attribution label |
| `start_time` | timestamp | Session start |
| `end_time` | timestamp | Session end (nullable) |
| `status` | enum | COMPLETED, ABORTED, FAILED |
| `termination_reason` | enum | See enums above |
| `recording_ref` | string | Object storage path |
| `recording_sha256` | string | Integrity checksum |

---

## Protocol Engines

### Engine Selection

```
┌─────────────────────────────────────────────────────────────────┐
│                    PROTOCOL ENGINE SELECTION                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  assetUID ──▶ jit-server ──▶ db_type ──▶ Engine Selection       │
│                                                                  │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐        │
│  │   Postgres  │     │  Cassandra  │     │   MongoDB   │        │
│  │   Engine    │     │   Engine    │     │   Engine    │        │
│  └──────┬──────┘     └──────┬──────┘     └──────┬──────┘        │
│         │                   │                   │                │
│         ▼                   ▼                   ▼                │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐        │
│  │ TLS+GSSAPI  │     │ TLS+SASL    │     │ TLS+SCRAM/  │        │
│  │             │     │ (if enabled)│     │ Kerberos/IAM│        │
│  └─────────────┘     └─────────────┘     └─────────────┘        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

> **Security**: jit-proxy **MUST NOT** trust prelude for `db_type`. Engine selection is based solely on the `db_type` returned by jit-server in the authorize response.

### Postgres/CockroachDB Engine

| Feature | Implementation |
|---------|----------------|
| Framing | Startup + typed messages |
| Audit | Query/Parse text, CommandComplete, ErrorResponse |
| Auth | TLS + GSSAPI via cred-service TGT |
| Cancel | Cancel-key mapping (see below) |

**Cancel-key mapping**: Postgres cancel requests arrive on a separate TCP connection with `(pid, secret_key)`. jit-proxy must:
1. Intercept `BackendKeyData` from backend and store mapping: `(backend_pid, backend_key) → db_session_id`
2. Generate synthetic `(frontend_pid, frontend_key)` to send to client
3. Maintain reverse mapping: `(frontend_pid, frontend_key) → (backend_pid, backend_key)`
4. On cancel request: look up backend key, forward to correct backend connection

> This enables query cancellation while hiding backend connection details from clients.

### Cassandra Engine

| Feature | Implementation |
|---------|----------------|
| Framing | Native protocol v3-v6, modern segments |
| Audit | QUERY/PREPARE CQL text, EXECUTE (requires prepared stmt mapping) |
| Auth | TLS + SASL/Kerberos (if enabled; some deployments use TLS-only) |

### MongoDB Engine

| Feature | Implementation |
|---------|----------------|
| Framing | Length-prefixed BSON |
| Audit | Command names, collections (not full documents) |
| Auth | TLS + SCRAM/Kerberos/IAM (deployment-dependent) |

### Early Phase: Pass-Through Mode

For Cassandra and MongoDB, early implementation phases may use **pass-through + minimal metadata logging** (connection events, byte counts) before upgrading to **structured command logging** with full protocol parsing.

---

## jit-proxy Netty Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    NETTY THREADING MODEL                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐                                            │
│  │   Boss Group    │  1 thread (acceptor)                       │
│  │                 │                                            │
│  └────────┬────────┘                                            │
│           │                                                      │
│           ▼                                                      │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    Worker Group                          │    │
│  │                  (~2 x CPU threads)                      │    │
│  │                                                          │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │    │
│  │  │ EventLoop 1 │  │ EventLoop 2 │  │ EventLoop N │      │    │
│  │  │             │  │             │  │             │      │    │
│  │  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │      │    │
│  │  │ │Frontend │ │  │ │Frontend │ │  │ │Frontend │ │      │    │
│  │  │ │ Channel │ │  │ │ Channel │ │  │ │ Channel │ │      │    │
│  │  │ └────┬────┘ │  │ └────┬────┘ │  │ └────┬────┘ │      │    │
│  │  │      │      │  │      │      │  │      │      │      │    │
│  │  │ ┌────▼────┐ │  │ ┌────▼────┐ │  │ ┌────▼────┐ │      │    │
│  │  │ │Backend  │ │  │ │Backend  │ │  │ │Backend  │ │      │    │
│  │  │ │ Channel │ │  │ │ Channel │ │  │ │ Channel │ │      │    │
│  │  │ └─────────┘ │  │ └─────────┘ │  │ └─────────┘ │      │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘      │    │
│  │                                                          │    │
│  │  Frontend + Backend on SAME EventLoop for efficiency     │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              Blocking Operations Executor                │    │
│  │  - HTTPS calls to jit-server/cred-service                │    │
│  │  - GSSAPI token generation (if slow)                    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Concurrency model**: Each EventLoop handles **many concurrent channels** (client + backend sockets). There is not one thread per connection. Concurrency is limited by CPU, memory, kernel socket limits, and blocking executor saturation.

### Non-Blocking Rule

**Never block the EventLoop thread:**
- HTTPS calls to jit-server and cred-service **must be asynchronous** (or offloaded to a dedicated executor)
- Results must be marshaled back to the channel's EventLoop
- GSSAPI token generation offloaded if slow

### Connection Pending State

Authorization (jit-server) and credential fetch (cred-service) **must complete before** jit-proxy starts forwarding DB bytes. The connection remains in a "pending" state during this phase, bounded by the total prelude→ready timeout (see Timeouts section).

### Blocking Executor Limits

Blocking executor is **bounded** with concurrency limits and timeouts. If saturated, new connections remain pending or are rejected with a "server busy" error.

### Timeouts

| Operation | Timeout | On Timeout |
|-----------|---------|------------|
| jit-server `/authorize` | 10s | `authorize_timeout` |
| cred-service credential fetch | 10s | `cred_failed` |
| Backend DB TCP connect | 10s | `db_connect_failed` |
| Backend DB auth (GSSAPI/SASL) | 15s | `db_auth_failed` |
| **Total prelude→ready** | 30s | First applicable reason |

> **Consistency note**: Total prelude→ready timeout (30s) is well under the ±120s anti-replay window. This ensures `ts_epoch_ms` remains valid throughout authorization without requiring re-authorization with fresh ts/nonce.

### Backpressure & Limits

| Condition | Action |
|-----------|--------|
| Blocking executor queue full | Reject with `server_busy` |
| Pending connections > N | Reject with `server_busy` |
| EventLoop latency > threshold | Shed load (reject new accepts) |
| HTTP client pool exhausted | Reject with `server_busy` |

> **Tuning**: Exact thresholds (N, latency threshold) are deployment-specific. Monitor p99 latency and queue depths to set appropriate values.

---

## Session Recording

### Recording Format (JSONL)

```json
{"ts":"2025-12-30T10:00:00Z","type":"SESSION_START","db_session_id":"uuid","bundle_id":"hash"}
{"ts":"2025-12-30T10:00:01Z","type":"QUERY","text":"SELECT * FROM users WHERE id = $1"}
{"ts":"2025-12-30T10:00:01Z","type":"RESULT","rows_affected":1}
{"ts":"2025-12-30T10:00:02Z","type":"QUERY","text":"UPDATE users SET name = $1 WHERE id = $2"}
{"ts":"2025-12-30T10:00:02Z","type":"RESULT","rows_affected":1}
{"ts":"2025-12-30T10:00:05Z","type":"SESSION_END","queries":2,"errors":0}
```

### Session Summary (to jit-server)

```json
{
  "db_session_id": "uuid",
  "bundle_id": "hash",
  "bundle_expires_at": "2025-12-30T12:00:00Z",
  "start_time": "2025-12-30T10:00:00Z",
  "end_time": "2025-12-30T10:00:05Z",
  "expired_while_connected": false,
  "status": "COMPLETED",
  "query_count": 2,
  "error_count": 0,
  "recording_ref": "s3://bucket/recordings/uuid.jsonl"
}
```

> `expired_while_connected`: true if `end_time > bundle_expires_at`. Aids compliance review.

### Recording Storage

- Recordings are written as local JSONL files on jit-proxy during the session
- On session end, recordings may be uploaded to object storage (S3, GCS, etc.)
- `recording_ref` can be a local path or object storage key; jit-server treats it as opaque
- **jit-server stores only session metadata + `recording_ref`**
- Integration with post-activity-review: jit-server **pushes** session summary (or review-server pulls on schedule); actual recording retrieval uses `recording_ref`
- Retention and cleanup policies are deployment-specific

### Recording Redaction Policy

JSONL recordings may contain sensitive data (PII, secrets in SQL literals). Per-database behavior:

| Database | Default Recording | Redaction Options |
|----------|-------------------|-------------------|
| **Postgres/CockroachDB** | Full SQL text (Query/Parse) | `full_text`, `parameterized`, `redacted` |
| **Cassandra** | QUERY/PREPARE CQL text; EXECUTE resolved via prepared map | Optional redaction if literals present |
| **MongoDB** | Command name + db/collection + namespace only | N/A (no full documents recorded) |

**Redaction modes** (Postgres/CockroachDB):

| Mode | Description |
|------|-------------|
| `full_text` | Record complete query text (default for compliance) |
| `parameterized` | Record query structure with `$N` placeholders; omit literal values |
| `redacted` | Apply regex-based redaction for known patterns (SSN, credit card, etc.) |

> **Note**: MongoDB never records full documents by design; only command metadata is captured.

### Recording Integrity

To detect tampering, jit-proxy computes a checksum on recording close:

- **Algorithm**: SHA-256 of final JSONL file
- **Storage**: Include `recording_sha256` in `/sessions/end` request and session metadata
- **Verification**: Post-activity-review can verify checksum before processing

```json
{
  "db_session_id": "uuid",
  "recording_ref": "s3://bucket/recordings/uuid.jsonl",
  "recording_sha256": "abc123..."
}
```

---

## Security Boundaries

```
┌─────────────────────────────────────────────────────────────────┐
│                     SECURITY BOUNDARIES                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    User Workstation                      │    │
│  │  ┌─────────┐          ┌─────────┐                       │    │
│  │  │ Client  │ ───────▶ │ pamjit  │                       │    │
│  │  └─────────┘localhost └────┬────┘                       │    │
│  └────────────────────────────┼────────────────────────────┘    │
│                               │ TLS (server-auth)                │
│  ═══════════════════════════════════════════════════════════    │
│                               │                                  │
│  ┌────────────────────────────▼────────────────────────────┐    │
│  │                    DMZ / Proxy Zone                      │    │
│  │                   ┌─────────────┐                        │    │
│  │                   │  jit-proxy  │                        │    │
│  │                   └──────┬──────┘                        │    │
│  └──────────────────────────┼──────────────────────────────┘    │
│                             │ HTTPS + Service JWT                 │
│  ═══════════════════════════════════════════════════════════    │
│                             │                                    │
│  ┌──────────────────────────▼──────────────────────────────┐    │
│  │                   Internal Services                      │    │
│  │  ┌───────────┐   ┌─────────────┐   ┌──────────────┐     │    │
│  │  │jit-server │   │ cred-service │   │   Database   │     │    │
│  │  │  (HTTPS)  │   │   (HTTPS)   │   │ (TLS+GSSAPI) │     │    │
│  │  └───────────┘   └─────────────┘   └──────────────┘     │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Connection Security

| Path | Security |
|------|----------|
| pamjit → jit-proxy | TLS (server-auth only) + end-user JWT in prelude |
| jit-proxy → jit-server | HTTPS + service JWT |
| jit-proxy → cred-service | HTTPS + service JWT |
| jit-proxy → Database | TLS + GSSAPI/SASL |

**Certificate trust (pamjit)**: pamjit MUST verify the jit-proxy server certificate (hostname + chain) and SHOULD use a pinned CA bundle or explicit trust store (deployment-specific) instead of the OS trust store.

### Rate Limiting

| Scope | Limit | Action |
|-------|-------|--------|
| Per service identity | Requests/sec to jit-server | 429 Too Many Requests |
| Per user (jwt_sub) | Authorize calls/min per asset | 429 + backoff hint |
| Per asset | Total concurrent sessions | Reject with `server_busy` |

> **Rationale**: Per-user/per-asset limits reduce brute-force attempts and prevent single user from exhausting resources.

### Target Validation

- Allowlist ports per db_type (e.g., 26257, 5432, 9042, 27017)
- Block localhost/link-local if jit-proxy in privileged zone
- Validate target against asset metadata

---

## Failure Handling

```
┌─────────────────────────────────────────────────────────────────┐
│                     FAILURE SCENARIOS                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Failure Point                        │ Action                            │
│  ─────────────────────────────────────┼─────────────────────────────────  │
│  Prelude parse/size/schema (jit-proxy)│ Reject prelude, close connection  │
│  ts/nonce replay detected (jit-proxy) │ Reject prelude, close connection  │
│  End-user JWT invalid (jit-server)    │ Reject via CONNECT_DECISION       │
│  No active grants (jit-server)        │ Reject via CONNECT_DECISION       │
│  cred-service fails                   │ Reject via CONNECT_DECISION       │
│  DB connect/auth fails                │ Reject via CONNECT_DECISION       │
│  DB protocol error (mid-session)      │ Log error, forward to client      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Session Expiry Policy

**Default behavior**: Allow DB session to continue until client disconnects, even if `bundle_expires_at` passes mid-session. SCIM revocation will eventually reduce privileges at the database level.

**Rationale**: Forcibly terminating active queries can cause data corruption or leave transactions in unknown state. The SCIM-based revocation provides eventual consistency.

> **Enforcement limitation**: Revocation effectiveness depends on the database's auth model (Kerberos ticket lifetime, role mapping refresh). SCIM revocation may not terminate already-established sessions; therefore we rely on audit + post-activity review, and optionally enforce hard disconnect at expiry.

**Reporting**: jit-proxy always reports `expired_while_connected: true` in session summary if the session outlived `bundle_expires_at`. This enables compliance review.

> **Alternative (stricter)**: jit-proxy can optionally enforce expiry by terminating sessions at `bundle_expires_at`. This requires explicit opt-in per deployment.

---

## Implementation Phases

### Phase 1: Core Infrastructure
1. Implement pamjit localhost listener + TLS to jit-proxy
2. Implement jit-proxy prelude validator (JWT + ts/nonce)
3. Implement jit-proxy → jit-server authorize API

### Phase 2: Credential & Database
4. Implement jit-proxy → cred-service credential fetch
5. Implement Postgres/CockroachDB engine with TLS + GSSAPI
6. Add Query/Result/Error recording

### Phase 3: Reporting
7. Add session start/end reporting to jit-server
8. Add recording storage and reference passing

### Phase 4: Multi-Protocol
9. Add Cassandra engine with SASL/Kerberos
10. Add MongoDB engine with command parsing

### Phase 5: Hardening
11. Target/port allowlisting
12. Rate limiting
13. Session expiry enforcement
14. Postgres cancel request support

**Key Design Principles:**
- jit-server is the **only** source of truth for grants
- jit-proxy is **stateless for grant authority** (does not persist approvals/grants; queries jit-server per connection); **stateful per connection** for protocol parsing, prepared statement maps, cancel key mapping, and recording
- Database clients remain **unmodified**
- All sessions are **recorded** for compliance
- `bundle_id` enables accurate **attribution** of sessions to grants
