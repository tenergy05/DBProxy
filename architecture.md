# JIT Database Access & Recording Architecture

## Overview

This document describes a **Just-In-Time (JIT) database access system** with **session recording** capabilities. The architecture enables time-limited, audited database access while keeping database clients (IntelliJ, DBeaver, psql, cqlsh, mongosh) completely unmodified.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ARCHITECTURE OVERVIEW                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌──────────┐  HTTPS  ┌──────────────┐  SCIM/HTTPS  ┌──────────────┐       │
│   │  jit-ui  │────────▶│  jit-server  │─────────────▶│ DB Control   │       │
│   │  (Web)   │         │ (Control)    │ grant/revoke │ Planes       │       │
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
3. **Complete audit trail** - All queries/commands recorded per session
4. **Time-bounded access** - Grants expire automatically via SCIM
5. **Multi-protocol support** - Postgres, Cassandra, MongoDB

---

## Components

### jit-ui (Web Interface)

**Role**: User-facing portal for access requests

- Collects asset selection, host/port, duration, ticket, roles
- Calls jit-server HTTPS endpoints
- Displays approval results and grant identifiers

### jit-server (Control Plane)

**Role**: Authorization hub and state machine

- Validates JWT identity
- Validates entitlements and ticket rules
- Connects to database control planes via SCIM (HTTPS) to grant/revoke roles
- Stores authoritative approval state in CockroachDB
- Computes `bundle_id` for session attribution

### cred-service (Credential Provider)

**Role**: Runtime credential distribution

- Provides Kerberos TGT/credential cache for database auth
- Returns tokens/credentials based on database type
- **Only called by jit-proxy** - no other component accesses cred-service directly
- jit-proxy authenticates to cred-service via mTLS or service token

### jit-proxy (Data Plane)

**Role**: Database proxy with recording

- Accepts TLS connections from pamjit
- Validates prelude (JWT, asset, anti-replay)
- Authorizes via jit-server
- Fetches credentials from cred-service
- Connects to backend database (TLS + GSSAPI)
- Records all queries/commands to JSONL
- Reports session lifecycle to jit-server

### pamjit (Local Agent)

**Role**: Client-side forwarder (tsh-like)

- Runs localhost listener for database clients
- Opens TLS connection to jit-proxy
- Sends authentication prelude
- Forwards bytes bidirectionally (protocol-agnostic)

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

---

## End-to-End Workflow

### Phase 1: Access Request (Control Plane)

```
┌──────────┐     ┌──────────┐     ┌─────────────┐     ┌────────────┐
│   User   │     │  jit-ui  │     │ jit-server  │     │DB Control  │
│          │     │          │     │             │     │  Planes    │
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

### Message Format

```
┌─────────────────────────────────────────────────────────────────┐
│                      PRELUDE MESSAGE                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┬────────────────────────────────────────────┐  │
│  │ uint32 len  │              JSON payload                   │  │
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

### Anti-Replay Protection

| Field | Purpose | Storage |
|-------|---------|---------|
| `ts` | Timestamp within 120s window | Not stored |
| `nonce` | Unique per request | TTL cache (~120s) |

---

## Authorization API (jit-server)

### Request: POST `/api/v1/db/connect/authorize`

```json
{
  "user_id": "derived-from-jwt-sub",
  "asset_uid": "asset-uuid",
  "target_host": "db.example.com",
  "target_port": 26257,
  "ts_epoch_ms": 1730000000000,
  "nonce_b64": "..."
}
```

### Response (Success)

```json
{
  "allowed": true,
  "bundle_id": "sha256-hash-of-sorted-activity-uids",
  "bundle_expires_at": "2024-11-01T12:00:00Z",
  "db_type": "cockroachdb"
}
```

### Response (Denied)

```json
{
  "allowed": false,
  "reason": "no_active_grants"
}
```

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
│  │ TLS+GSSAPI  │     │ TLS+SASL/   │     │ TLS+SCRAM   │        │
│  │ Auth        │     │ Kerberos    │     │ (optional)  │        │
│  └─────────────┘     └─────────────┘     └─────────────┘        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

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
| Auth | TLS + SASL/Kerberos |

### MongoDB Engine

| Feature | Implementation |
|---------|----------------|
| Framing | Length-prefixed BSON |
| Audit | Command names, collections (not full documents) |
| Auth | TLS + SCRAM (deployment-dependent) |

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

### Non-Blocking Rule

**Never block the EventLoop thread:**
- HTTPS calls must be async or offloaded
- GSSAPI token generation offloaded if slow
- Results written back on EventLoop

---

## Session Recording

### Recording Format (JSONL)

```json
{"ts":"2024-11-01T10:00:00Z","type":"SESSION_START","db_session_id":"uuid","bundle_id":"hash"}
{"ts":"2024-11-01T10:00:01Z","type":"QUERY","text":"SELECT * FROM users WHERE id = $1"}
{"ts":"2024-11-01T10:00:01Z","type":"RESULT","rows_affected":1}
{"ts":"2024-11-01T10:00:02Z","type":"QUERY","text":"UPDATE users SET name = $1 WHERE id = $2"}
{"ts":"2024-11-01T10:00:02Z","type":"RESULT","rows_affected":1}
{"ts":"2024-11-01T10:00:05Z","type":"SESSION_END","queries":2,"errors":0}
```

### Session Summary (to jit-server)

```json
{
  "db_session_id": "uuid",
  "bundle_id": "hash",
  "start_time": "2024-11-01T10:00:00Z",
  "end_time": "2024-11-01T10:00:05Z",
  "status": "COMPLETED",
  "query_count": 2,
  "error_count": 0,
  "recording_ref": "s3://bucket/recordings/uuid.jsonl"
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
│                             │ mTLS / Service Token               │
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
| jit-proxy → jit-server | HTTPS (mTLS or service token) |
| jit-proxy → cred-service | HTTPS (mTLS or service token) |
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
│  cred-service fails          │ Reject, optionally report attempt │
│  DB connect/auth fails      │ Report session ABORTED, close     │
│  DB protocol error          │ Log error, forward to client      │
│  Session expires mid-flight │ Option: allow continue or close   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

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
- jit-proxy is **stateless** (queries jit-server per connection)
- Database clients remain **unmodified**
- All sessions are **recorded** for compliance
- `bundle_id` enables accurate **attribution** of sessions to grants
