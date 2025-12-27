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
| **mTLS** | Mutual TLS (client and server certificates) |
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
| `bundle_id` | jit-server | Labels session with set of active grants |

### Why bundle_id?

A user may have multiple concurrent grants for the same asset (multiple roles approved at different times). At DB session start, effective privileges are the **union** of all active grants. `bundle_id` labels that union for accurate post-activity attribution.

> **Important**: `bundle_id` is computed from the active `activityUID` set at authorization time. It is **not a credential** and is not used for authorization—it is purely an attribution label for reporting and compliance.

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

- TLS over TCP (server-auth minimum)
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

### Anti-Replay Protection

| Field | Purpose | Validation |
|-------|---------|------------|
| `ts` | Timestamp freshness | jit-proxy: sanity check; **jit-server: authoritative** |
| `nonce` | Unique per request | **jit-server** shared TTL store (2-5 min) |

**Validation split**:
- **jit-proxy**: lightweight ts sanity (drop obviously stale/future timestamps, e.g., >5 min skew); best-effort local nonce cache using `sha256(raw_jwt + asset_uid + nonce)` as key
- **jit-server**: authoritative ts window check (rejects `ts_epoch_ms` outside ±120s even if nonce is new) + authoritative nonce uniqueness via shared TTL store (**recommended: Redis**). CockroachDB can be used with unique constraint + TTL cleanup, but is not preferred due to write load/latency.

**Nonce cache key** (jit-server): `hash(jwt_sub + asset_uid + nonce)` — computed after JWT validation, prevents replay across users/assets.

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
  "asset_uid": "asset-uuid",
  "target_host": "db.example.com",
  "target_port": 26257,
  "ts_epoch_ms": 1730000000000,
  "nonce_b64": "..."
}
```

> **Note**: User identity is derived from `X-End-User-JWT`, not from the request body. jit-server validates the end-user JWT and extracts claims.

**Response (Success)**

```json
{
  "allowed": true,
  "bundle_id": "sha256-hash-of-sorted-activity-uids",
  "bundle_expires_at": "2025-12-30T12:00:00Z",
  "db_type": "cockroachdb"
}
```

**Response (Denied)**

```json
{
  "allowed": false,
  "reason": "no_active_grants"
}
```

### POST `/api/v1/db/sessions/start`

Called by jit-proxy when DB connection is established.

**Headers**: `Authorization: Bearer <service_jwt>` only (no end-user JWT required; session is correlated by `bundle_id` + `db_session_id`).

### POST `/api/v1/db/sessions/end`

Called by jit-proxy when DB session terminates (includes summary).

**Headers**: `Authorization: Bearer <service_jwt>` only.

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

Authorization (jit-server) and credential fetch (cred-service) **must complete before** jit-proxy starts forwarding DB bytes. The connection remains in a "pending" state during this phase. If these calls take seconds to minutes, the client will wait—this is expected behavior.

### Blocking Executor Limits

Blocking executor is **bounded** with concurrency limits and timeouts. If saturated, new connections remain pending or are rejected with a "server busy" error.

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
| pamjit → jit-proxy | TLS (server-auth minimum) |
| jit-proxy → jit-server | HTTPS + service JWT |
| jit-proxy → cred-service | HTTPS + service JWT |
| jit-proxy → Database | TLS + GSSAPI/SASL |

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
│  Failure Point              │ Action                            │
│  ───────────────────────────┼─────────────────────────────────  │
│  JWT validation fails       │ Reject prelude, close connection  │
│  ts/nonce replay detected   │ Reject prelude, close connection  │
│  jit-server authorize fails │ Reject before DB bytes forwarded  │
│  cred-service fails         │ Reject, optionally report attempt │
│  DB connect/auth fails      │ Report session ABORTED, close     │
│  DB protocol error          │ Log error, forward to client      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Session Expiry Policy

**Default behavior**: Allow DB session to continue until client disconnects, even if `bundle_expires_at` passes mid-session. SCIM revocation will eventually reduce privileges at the database level.

**Rationale**: Forcibly terminating active queries can cause data corruption or leave transactions in unknown state. The SCIM-based revocation provides eventual consistency.

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
