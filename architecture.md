# DBProxy Architecture (Java/Netty Prototype)

## Goal
Teleport-style (v18.5.1) database proxy in Java that preserves native wire protocols (Postgres, Mongo, Cassandra), adds pluggable auth (JWT placeholder), routing, and audit hooks, while keeping clients (psql/JDBC/IntelliJ, mongo shell/driver, cqlsh/driver) unchanged.

## Top-Level Structure
- **core**: DB-agnostic plumbing.
  - `BackendConnector`: dials backend using frontend event loop with a supplied pipeline initializer.
  - `BackendHandler`: streams backend → frontend.
  - `MessagePump`: ties lifecycles and flush-closes channels.
  - `audit/*`: `AuditRecorder`, `DbSession`, `Query`, `Result`, `LoggingAuditRecorder`.
- **postgres**: PG-specific framing, parsing, proxy server.
  - `PostgresProxyServer`: Netty server bootstrap; per-connection session, JWT validation, routing to backend host/port; optional listener TLS via Netty `SslHandler`.
  - `PostgresFrameDecoder`: PG frame splitter (startup vs typed messages). Public so other packages can reuse in pipelines.
  - `PgMessages`: PG frontend parsing (Startup/Cancel/Query/Parse/Bind/Execute/Password/Terminate) and helpers (encode query, auth ok/cleartext, error response).
  - `FrontendHandler`: parses client messages, validates JWT (PasswordMessage), resolves backend, forwards frames, emits audit.
  - `PostgresBackendAuditHandler`: inspects backend CommandComplete/ErrorResponse → audit result. Public ctor for cross-package pipeline wiring.
  - `QueryLogger`/`LoggingQueryLogger`: query inspection/rewrite hooks.
  - `postgres.auth.PgGssBackend`: TLS + GSSAPI (Kerberos) backend connector; builds a backend pipeline with SSL, frame decoder, audit, and a GSS handshake handler.
- **mongo**: Length-prefixed framing, passthrough proxy with request logger.
  - `MongoProxyServer`, `MongoFrontendHandler`, `MongoFrameDecoder`, `MongoRequestLogger`/`LoggingMongoRequestLogger`.
- **cassandra**: Length-prefixed framing, passthrough proxy with request logger.
  - `CassandraProxyServer`, `CassandraFrontendHandler`, `CassandraFrameDecoder`, `CassandraRequestLogger`/`LoggingCassandraRequestLogger`.

## Current Auth Model (JWT Placeholder)
- PG: after StartupMessage, proxy challenges with `AuthenticationCleartextPassword`; treats `PasswordMessage` contents as JWT. The proxy consumes this message and never forwards it to the backend; backend auth uses route credentials (e.g., GSS/password).
- `jwtValidator` (Config) decides accept/reject. Default is permissive; replace with real JWT verification (signature, exp, claims).
- On invalid JWT: sends PG `ErrorResponse` and closes.
- On success: records session start in audit, then connects to backend and forwards traffic.
- Mongo/Cassandra: currently only hex-logging; you can insert JWT validation before connecting.

### Postgres Auth Modes
- **Mode A (implemented):** proxy issues `AuthenticationCleartextPassword`; client sends JWT in `PasswordMessage`. Proxy validates JWT, authenticates to backend using route credentials, then completes client login.
- **Mode B (planned):** JWT provided in a prelude before any PG frames (agent-only). Not implemented.
- Direct clients (psql/JDBC/IntelliJ) must paste JWT as the password unless a local agent injects it.

## Routing Model
- `PostgresProxyServer.Config` exposes `TargetResolver` to choose backend target (host/port + auth material) per connection, based on `DbSession` (user/db/app) and JWT string.
- Convenience `addRoute(databaseName, Route)` with `*` default. Example:
  ```java
  new PostgresProxyServer.Config()
      .addRoute("sales", new PostgresProxyServer.Route("pg-sales.internal", 5432, "sales_user", "sales", null, null, null, null, null, null))
      .addRoute("*", new PostgresProxyServer.Route("127.0.0.1", 26257, "postgres", null, null, null, null, null, null, null));
  ```
- Modify `TargetResolver` to decode JWT claims for richer routing.

## Audit Hooks
- `AuditRecorder` mirrors Teleport semantics (`onSessionStart`, `onSessionEnd`, `onQuery`, `onResult`); default `LoggingAuditRecorder` logs JSON-like maps to SLF4J.
- PG: `FrontendHandler` emits `onSessionStart` on JWT success, `onQuery` for Query messages; `PostgresBackendAuditHandler` emits `onResult` on CommandComplete/ErrorResponse; `onSessionEnd` on disconnect.
- Extend/replace `LoggingAuditRecorder` to send events to a real sink.

## Local Config (JSON)
- Default main loads `config.sample.json` from classpath; override by passing a path arg if needed.
- Load programmatically with `PostgresProxyServer.Config.fromJson(path)` or `fromClasspath("config.sample.json")`.
- Shape:
  ```json
  (see `src/main/resources/config.sample.json` for a complete example with TLS/Kerberos fields and 3 routes)
  ```
- Unknown fields are ignored; missing database/host/port per route cause a load error. `selfSigned: true` builds a self-signed listener TLS context; omit `tls` to disable listener TLS.

## Postgres Data Flow & Pipelines
- **Frontend pipeline (client → proxy)**: optional TLS listener `SslHandler` (if configured) → `PostgresFrameDecoder(true)` → `FrontendHandler`. Decoder handles startup frame as length-prefixed without leading type, then typed messages.
- **FrontendHandler path**:
  - Parses messages with `PgMessages.parseFrontend` (pgproto3-style structs).
  - SSLRequest/GSSENCRequest: responds `N` (deny upgrade), keeps waiting for startup.
  - StartupMessage: capture `user`, `database`, `application_name` into `DbSession`.
  - PasswordMessage: treat password field as JWT, validate via `jwtValidator`; on success call `auditRecorder.onSessionStart`.
  - Query / Parse / Bind / Execute / Terminate: invoke `QueryLogger`; Query triggers optional rewrite and `auditRecorder.onQuery`.
  - Buffers frames until backend is connected and authenticated; on failure sends `ErrorResponse` and closes.
- **Backend connect**: `TargetResolver` selects `Route` (host/port/dbUser/dbName/TLS+Kerberos options). `PgGssBackend.connect` dials backend on frontend event loop.
- **Backend pipeline (proxy → PG server)**: `SslHandler` (TLS 1.2/1.3 client) → `PostgresFrameDecoder(false)` → `PostgresBackendAuditHandler` → GSS handshake handler (AuthenticationGSS/GSSContinue, Password token writes) → `BackendHandler` (streaming).
- **Linking**: After backend auth ok, `MessagePump.link(frontend, backend)` mirrors bytes both ways; `MessagePump.closeOnFlush` on disconnect. Pending frontend frames are flushed only after backend auth succeeds.

## Build & Run
- Maven (`pom.xml`, Java 17, Netty 4.1.108.Final). Build:
  ```bash
  mvn -DskipTests package
  ```
  If ~/.m2 permissions are restricted, set an alternate repo: `mvn -Dmaven.repo.local=/tmp/m2 -DskipTests package`.
- Run PG proxy (examples):
  - Default (classpath config.sample.json): `java -jar target/dbproxy-0.1.0-SNAPSHOT.jar`
  - Override config file: `java -jar target/dbproxy-0.1.0-SNAPSHOT.jar /path/to/config.json`
  - Programmatic (for embedding):
    ```java
    var cfg = new PostgresProxyServer.Config()
        .addRoute("*", new PostgresProxyServer.Route("127.0.0.1", 5432, "postgres", null, null, null, null, null, null, null))
        .jwtValidator(token -> true); // TODO real JWT check
    new PostgresProxyServer(cfg).start();
    ```
  Connect with psql/JDBC/IntelliJ to proxy host/port, DB/user as normal; put JWT in password field.

## Postgres Protocol Coverage (Java prototype)
- **Parsed / inspected in `FrontendHandler`**: SSLRequest/GSSENCRequest (responds `N`), StartupMessage, CancelRequest (forwarded verbatim), PasswordMessage, Query, Parse, Bind, Execute, Describe, Close, Sync, Flush, CopyData/CopyDone/CopyFail, FunctionCall, Terminate. Unknown messages are passed through without inspection.
- **Not parsed/handled**: SASLInitialResponse/SASLResponse, Startup parameter status replies, CopyIn/CopyOut contents, portal/statement lifecycle state machine, ReadyForQuery semantics, compression, length guarding beyond basic bounds.
- **TLS negotiation**: Listener TLS (if enabled) expects TLS from byte 0; SSLRequest/GSSENCRequest are explicitly denied (`N`) rather than upgrading. Clients must use `sslmode=disable` unless traffic is wrapped externally (stunnel/ALB) or uses TLS-from-start.
- **Auth modes**: Only AuthenticationCleartextPassword challenge is emitted; no MD5/SCRAM/SASL support on frontend; backend auth is GSS (Kerberos) via `PgGssBackend`.
- **Backend auditing**: `PostgresBackendAuditHandler` sits before the GSS handler; it ignores auth chatter but emits `onResult` on backend CommandComplete ('C') and ErrorResponse ('E'); other messages are forwarded without audit semantics.
- **Cancel flow**: CancelRequest is parsed, but proper PID/secret-based cancel routing with a dedicated cancel connection is not implemented.
- **SSL / TLS differences vs Go**: Teleport’s Go DB engine performs PG SSL negotiation (responds 'S'/'N') and supports cancel protocol; this Java prototype either uses listener TLS from byte 0 or denies SSLRequest/GSSENC (`N`) and expects out-of-band TLS.

## Gaps vs Teleport Go Implementation
- Reference Go engine: `teleport/lib/srv/db/postgres/engine.go` (uses `jackc/pgproto3` to fully parse frontend/backed messages, cancel flow, SASL/MD5/SCRAM auth, SSL negotiation).
- Missing in Java prototype: SASL/MD5/SCRAM auth flows, portal/statement lifecycle tracking + ready-for-query state machine, server ParameterStatus/BackendKeyData forwarding, cancel routing keyed by PID/secret, compression, pgproto-level validation. SSLRequest/GSSENC are denied (`N`) rather than upgrading.
- Listener TLS in Java assumes TLS from byte 0 (or explicit denial); Go path speaks native PG SSL negotiation to decide TLS.
- Backend auth: Java uses GSS ticket cache; Go supports DB-specific TLS (verify-full/verify-ca/insecure) and driver-side auth variants.
- To reach Go-level coverage, Java frontend must grow a stateful PG protocol implementation or embed a pgproto3-equivalent; netty decode/encode currently inspects only a narrow subset.

## Postgres Backend Connection (TLS + GSSAPI)
- Backend path (after frontend JWT ok) can use `PgGssBackend.connect` to reach a PG server with TLS (client mode) and GSSAPI auth.
- Backend pipeline: `SslHandler` (JDK provider, TLS 1.2/1.3) → `PostgresFrameDecoder(false)` → `PostgresBackendAuditHandler` → GSS handshake handler that exchanges AuthenticationGSS/Continue, sends password messages with GSS tokens, then swaps to `BackendHandler` for streaming.
- GSS setup:
  - Builds service principal from route (`servicePrincipal` or `postgres/<host>` default).
  - Uses Kerberos ticket cache (`useTicketCache=true`, `doNotPrompt=true`); optional overrides via route: `krb5ConfPath`, `krb5CcName`, `clientPrincipal`.
  - Wraps `Subject.doAs` around JGSS `initSecContext`; errors surfaced as `IllegalStateException` with the underlying cause.
- TLS trust: route may specify `caCertPath` (trust anchor) and `serverName` (SNI/verification). Falls back to `InsecureTrustManagerFactory` when CA not provided (dev-only; provide a CA or system trust for production).
- On AuthenticationOk, links frontend/backend via `MessagePump` and emits audit via backend handler.

## Protocol Notes (Postgres)
- Frames: startup (length-prefixed, no type), then typed messages (type byte + length).
- Auth: `AuthenticationCleartextPassword` prompt, accept `PasswordMessage` as JWT, then proceed. Listener TLS is optional via config (no PG-level SSL negotiation; SSLRequest/GSSENC are denied); backend dialer uses TLS when configured via `PgGssBackend`.
- Backend readiness: frontend frames are buffered until backend auth succeeds; backend audit handler observes server replies after auth.
- Cancel requests: not implemented in Java skeleton yet (exists in Teleport Go).

## Mongo Path (Current)
- Pipeline: `MongoFrameDecoder` (length-prefixed) → `MongoFrontendHandler`; `BackendConnector` dials backend with matching frame decoder and `BackendHandler`.
- No auth/JWT/TLS implemented; proxy is plaintext passthrough with optional `MongoRequestLogger` that dumps requests in hex.
- Missing features vs production: TLS to client/backend, scram/kerberos auth, structured command parsing/audit, compressions (snappy/zlib/zstd) awareness.

## Cassandra Path (Current)
- Pipeline: `CassandraFrameDecoder` (length-prefixed) → `CassandraFrontendHandler`; `BackendConnector` mirrors to backend decoder/handler.
- No auth/JWT/TLS; passthrough with `CassandraRequestLogger` hex dumps.
- Missing features: TLS on listener/backend, native protocol version negotiation, startup/auth flow parsing, tracing/audit hooks beyond hex log, compression flags.

## Extension Points / TODO
- Implement real JWT validation (signature, exp, audience) and map claims to routing + session metadata.
- Add TLS (server-only or mTLS) on the proxy listener to avoid cleartext JWT.
- Implement PG cancel flow and fuller message coverage in `PostgresBackendAuditHandler`.
- Mongo/Cassandra: add auth and structured parsing for audit; today they are length-framed pass-through with hex logging.
- Replace `LoggingAuditRecorder` with Teleport-compatible emitter if integrating back to Teleport services.

## Parallels to Teleport’s Implementation
- Client side (Teleport tsh): opens a local TCP listener per database, authenticates to Teleport Proxy over mTLS using Teleport-issued client certs/ALPN/SNI, and forwards raw DB protocol. tsh does not parse Postgres/Mongo/Cassandra.
- Proxy side (Teleport Proxy): accepts DB protocol, authorizes Teleport identity, forwards startup to DB service over reverse tunnel, streams bytes; it does not handle target DB TLS.
- DB service side (Teleport engines): protocol-aware; parses startup, RBAC check, optional auto user provision, connects to actual DB using per-DB TLS config, sends protocol auth OK to the client, relays messages, emits audit events.
- Per-DB TLS (Teleport): `Auth.GetTLSConfig` builds a `tls.Config` per Database resource. Uses DB-specific CA (from resource or cloud roots), sets ServerName/mode (verify-full/verify-ca/insecure), and for on-prem generates a client cert from Teleport’s CA. TLS is applied per session when dialing the backend DB; clients remain unaware.
- Session tracking/audit (Teleport): emitted on DB service side (session start/end, query, result); proxy keeps connections open and streams.

## Proposed JWT-Based tsh Replacement
- Goal: replace Teleport’s mTLS hop with JWT while keeping native DB protocols unchanged.
- Agent (Python/Java):
  - Start a local listener per DB (cockroach1, cockroach2, mongo, etc.).
  - On accept, connect to the Java proxy and authenticate with JWT (either prelude or responding to PG AuthenticationCleartextPassword with PasswordMessage carrying JWT).
  - Forward bytes after auth; no protocol parsing required in the agent.
  - Optionally wrap agent→proxy in server-only TLS to protect JWT in transit.
- Proxy:
  - Validate JWT (replace `jwtValidator` stub).
  - Resolve backend via `TargetResolver` (optionally decode JWT claims).
  - Connect to backend DB with per-DB TLS, send protocol auth OK, stream traffic.
  - Emit audit events (session start/end, query, result).

## Session Tracking (Current Java Proxy)
- Per connection: `DbSession` (ID, start time, client address, db/user/app from PG startup).
- `onSessionStart` on successful JWT validation; `onSessionEnd` on disconnect.
- PG: `onQuery` on Query messages; `onResult` on backend CommandComplete/ErrorResponse via `PostgresBackendAuditHandler`.
