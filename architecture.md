# DBProxy Architecture (Java/Netty Prototype)

## Goal
Teleport-style (v18.5.1) database proxy in Java that preserves native wire protocols (Postgres, Mongo, Cassandra) with Teleport-like naming/layout. Clients (psql/JDBC/IntelliJ, mongo shell/driver, cqlsh/driver) remain unaware of auth; the proxy completes SASL/GSS (Kerberos) to the backend using its own credentials.

Protocol selection is determined by the listener/engine or routing metadata, not by sniffing bytes.

## Top-Level Structure
- **core**: DB-agnostic plumbing.
  - `BackendConnector`: dials backend using frontend event loop with a supplied pipeline initializer.
  - `BackendHandler`: streams backend → frontend.
  - `MessagePump`: ties lifecycles and flush-closes channels.
  - `audit/*`: `AuditRecorder`, `Session`, `Query`, `Result`, `LoggingAuditRecorder`.
- **postgres**: Postgres (PG)-specific framing, parsing, proxy server.
  - `PostgresEngine`: Netty server bootstrap; per-connection session metadata and routing to backend host/port; optional listener TLS via Netty `SslHandler`.
  - `PostgresFrameDecoder`: PG frame splitter (startup vs typed messages). Public so other packages can reuse in pipelines.
  - `PgMessages`: PG frontend parsing (Startup/Cancel/Query/Parse/Bind/Execute/Password/Terminate) and helpers (encode query, auth ok/cleartext, error response).
  - `FrontendHandler`: parses client messages, ignores client passwords (proxy owns backend auth), resolves backend, forwards frames, emits audit.
  - `PostgresBackendAuditHandler`: inspects backend CommandComplete/ErrorResponse → audit result. Public ctor for cross-package pipeline wiring.
  - `QueryLogger`/`LoggingQueryLogger`: query inspection/rewrite hooks.
  - `postgres.auth.PgGssBackend`: TLS + GSSAPI (Generic Security Services API, Kerberos) backend connector; builds a backend pipeline with SSL, frame decoder, audit, and a GSS handshake handler.
- **mongo**: Length-prefixed framing, passthrough proxy with request logger.
  - `MongoEngine`, `MongoFrontendHandler`, `MongoFrameDecoder`, `MongoRequestLogger`/`LoggingMongoRequestLogger`.
- **cassandra**: Cassandra-native framing (v4/v5/v6; modern segments) engine with proxy-terminated SASL/GSS (Kerberos) to the backend; client sees a ready session without supplying credentials. Request logger can parse queries for audit-style logs.
  - `CassandraEngine` (listener), `CassandraFrontendHandler`, `CassandraBackendHandler`, `CassandraFrameDecoder`, `CassandraRequestLogger`/`LoggingCassandraRequestLogger`.

## Current Auth Model (proxy-owned)
- Postgres: frontend never authenticates clients. `PasswordMessage` is dropped; the proxy authenticates to the backend with Kerberos via `PgGssBackend` using route/service principal/krb5 settings. `onSessionStart` fires once backend dial/auth completes (success or error).
- Cassandra: client `AUTH_RESPONSE` is parsed for username/driver info but credentials are ignored. The proxy answers backend `AUTHENTICATE`/`AUTH_CHALLENGE` with its own GSS tokens (`CassandraGssAuthenticator`), forwards `AUTH_SUCCESS/READY`, and clients see a ready session without supplying credentials. Failed handshakes install `CassandraFailedHandshakeHandler` that replies SUPPORTED → AUTHENTICATE → AUTH_ERROR before closing.
- Mongo: passthrough with no auth.
- Prelude/agent (pamjit/tsh equivalent) can be layered later to authenticate/authorize to the proxy; the proxy already mirrors Teleport engine naming/layout and assumes identity is established upstream.

## Routing Model
- `PostgresEngine.Config` exposes `TargetResolver` to choose backend target (host/port + auth material) per connection, based on `Session` (user/db/app). Default resolver maps the startup `database` to a `Route` with `*` fallback.
- Convenience `addRoute(databaseName, Route)` with `*` default. Example (JSON is easiest; see `src/main/resources/config.sample.json`):
  ```json
  {
    "database": "sales",
    "host": "pg-sales.internal",
    "port": 5432,
    "dbUser": "sales_user",
    "dbName": "sales",
    "caCertPath": "/etc/dbproxy/pg-sales-ca.pem",
    "serverName": "pg-sales.internal",
    "servicePrincipal": "postgres/pg-sales.internal",
    "krb5CcName": "/tmp/krb5cc_sales",
    "krb5ConfPath": "/etc/krb5.conf",
    "clientPrincipal": "svc-sales@EXAMPLE.COM"
  }
  ```
- Route auth intent: today routes imply Kerberos/GSS (ticket cache) when Kerberos fields are set; password-based backend auth would require adding an explicit `auth` mode (future work).
- `TargetResolver` signature: `Route resolve(Session session)` (no JWT parameter).

## Audit Hooks
- `AuditRecorder` mirrors Teleport semantics (`onSessionStart`, `onSessionEnd`, `onQuery`, `onResult`); default `LoggingAuditRecorder` logs JSON-like maps to SLF4J.
- Session fields include Teleport-like metadata: `clusterName`, `hostId`, `databaseService`, `databaseType`, `databaseProtocol`, `identityUser`, `autoCreateUserMode`, `databaseRoles`, `startupParameters`, `lockTargets`, `postgresPid`, `userAgent`, plus db/user/app/protocol.
- PG: `onSessionStart` fires when backend dial/auth succeeds or fails; `onQuery` on Query messages; `PostgresBackendAuditHandler` emits `onResult` on CommandComplete/ErrorResponse; `onSessionEnd` on disconnect.
- Cassandra: `onSessionStart` on READY/AUTH_SUCCESS or failed handshake; `onQuery` for parsed Query/Prepare/Execute/Batch/Register; `onSessionEnd` on disconnect.
- Mongo: no structured audit yet beyond request logger hooks.
- Teleport parity: only session/query/result events are emitted—handshake frames and secrets are not logged. Query text is unredacted in the default logger.

## Local Config (JSON)
- Default main loads `config.sample.json` from classpath; override by passing a path arg if needed.
- Load programmatically with `PostgresEngine.Config.fromJson(path)` or `fromClasspath("config.sample.json")`.
- Shape:
  ```json
  (see `src/main/resources/config.sample.json` for a complete example with TLS/Kerberos fields and 3 routes)
  ```
- Unknown fields are ignored; missing database/host/port per route cause a load error. `selfSigned: true` builds a self-signed listener TLS context; omit `tls` to disable listener TLS.

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
    var cfg = new PostgresEngine.Config()
        .addRoute("*", new PostgresEngine.Route("127.0.0.1", 5432, "postgres", null, null, null, null, null, null, null));
    new PostgresEngine(cfg).start();
    ```
  Connect with psql/JDBC/IntelliJ to proxy host/port; leave password empty (or any string) because the proxy ignores it and authenticates to the backend with Kerberos.
- Run Cassandra engine for local testing (listens 19042 → 9042):
  ```bash
  java -cp target/dbproxy-0.1.0-SNAPSHOT.jar com.poc.pamport.cassandra.CassandraEngine
  ```
  Configure Kerberos fields on `CassandraEngine.Config` so the proxy performs SASL/GSS to Cassandra. Clients connect to localhost:19042 with any protocol version (v4/v5/v6); no credentials required.
- Mongo engine: same pattern via `com.poc.pamport.mongo.MongoEngine` (plaintext passthrough).

## Postgres Data Flow & Pipelines
- **Frontend pipeline (client → proxy)**: optional TLS listener `SslHandler` (if configured) → `PostgresFrameDecoder(true)` → `FrontendHandler`. Decoder handles startup frame as length-prefixed without leading type, then typed messages.
- **FrontendHandler path**:
  - Parses messages with `PgMessages.parseFrontend`.
  - SSLRequest/GSSENCRequest: responds `N` (deny upgrade), keeps waiting for startup.
  - StartupMessage: capture `user`, `database`, `application_name` into `Session`; stash startup parameters and user agent.
  - PasswordMessage: dropped; proxy owns backend auth.
  - Query / Parse / Bind / Execute / Terminate: invoke `QueryLogger`; Query triggers optional rewrite and `auditRecorder.onQuery`.
  - Frames are buffered until backend is connected/authenticated; on backend failure an `ErrorResponse` is sent and `onSessionStart` fires with the error.
- **Backend connect**: `TargetResolver` selects `Route` (host/port/dbUser/dbName/TLS+Kerberos options). `PgGssBackend.connect` dials backend on frontend event loop.
- **Backend pipeline (proxy → PG server)**: `SslHandler` (TLS 1.2/1.3 client) → `PostgresFrameDecoder(false)` → `PostgresBackendAuditHandler` → GSS handshake handler (AuthenticationGSS/GSSContinue, Password token writes) → `BackendHandler`.
- **Linking**: After backend auth ok, proxy forwards backend AuthenticationOk/BackendKeyData/ParameterStatus/ReadyForQuery to the client, then `MessagePump.link(frontend, backend)` mirrors bytes both ways; pending frontend frames are flushed after backend auth succeeds.

## Postgres Protocol Coverage (Java prototype)
- **Parsed / inspected in `FrontendHandler`**: SSLRequest/GSSENCRequest (responds `N`), StartupMessage, CancelRequest (forwarded), PasswordMessage (ignored), Query, Parse, Bind, Execute, Describe, Close, Sync, Flush, CopyData/CopyDone/CopyFail, FunctionCall, Terminate. Unknown messages are passed through.
- **Not parsed/handled**: SASLInitialResponse/SASLResponse, Startup parameter status replies, CopyIn/CopyOut contents, portal/statement lifecycle state machine, ReadyForQuery semantics, compression, length guarding beyond basic bounds.
- **TLS negotiation**: Listener TLS (if enabled) expects TLS from byte 0; SSLRequest/GSSENCRequest are denied (`N`) rather than upgrading. Clients should use `sslmode=disable` unless traffic is wrapped externally.
- **Auth modes**: Client auth is disabled (PasswordMessage ignored). Backend auth is Kerberos via `PgGssBackend`; SCRAM/MD5/SASL to the backend are not supported yet.
- **Backend auditing**: `PostgresBackendAuditHandler` emits `onResult` on backend CommandComplete/ErrorResponse; other messages are forwarded without audit semantics.
- **Cancel flow**: CancelRequest is parsed, but proper PID/secret-based cancel routing with a dedicated cancel connection is not implemented.
- **SSL / TLS differences vs Go**: Teleport’s Go DB engine performs PG SSL negotiation (responds 'S'/'N') and supports cancel protocol; this Java prototype either uses listener TLS from byte 0 or denies SSLRequest/GSSENC (`N`).

## Gaps vs Teleport Go Implementation
- Reference Go engine: `teleport/lib/srv/db/postgres/engine.go` (uses `jackc/pgproto3` to fully parse frontend/backend messages, cancel flow, SASL/MD5/SCRAM auth, SSL negotiation).
- Missing in Java prototype: SASL/MD5/SCRAM auth flows, portal/statement lifecycle tracking + ready-for-query state machine, server ParameterStatus/BackendKeyData forwarding, cancel routing keyed by PID/secret, compression, pgproto-level validation. SSLRequest/GSSENC are denied (`N`) rather than upgrading.
- Listener TLS in Java assumes TLS from byte 0 (or explicit denial); Go path speaks native PG SSL negotiation to decide TLS.
- Backend auth: Java uses GSS ticket cache; Go supports DB-specific TLS (verify-full/verify-ca/insecure) and driver-side auth variants.
- To reach Go-level coverage, Java frontend must grow a stateful PG protocol implementation or embed a pgproto3-equivalent; netty decode/encode currently inspects only a narrow subset.

## Postgres Backend Connection (TLS + GSSAPI)
- Backend path uses `PgGssBackend.connect` to reach a PG server with TLS (client mode) and GSSAPI auth.
- Backend pipeline: `SslHandler` (JDK provider, TLS 1.2/1.3) → `PostgresFrameDecoder(false)` → `PostgresBackendAuditHandler` → GSS handshake handler that exchanges AuthenticationGSS/Continue, sends password messages with GSS tokens, then swaps to `BackendHandler` for streaming.
- GSS setup:
  - Builds service principal from route (`servicePrincipal` or `postgres/<host>` default).
  - Uses Kerberos ticket cache (`useTicketCache=true`, `doNotPrompt=true`); optional overrides via route: `krb5ConfPath`, `krb5CcName`, `clientPrincipal`.
  - Wraps `Subject.doAs` around JGSS `initSecContext`; errors surfaced as `IllegalStateException` with the underlying cause.
- TLS trust: route may specify `caCertPath` (trust anchor) and `serverName` (SNI/verification). Falls back to `InsecureTrustManagerFactory` when CA not provided (dev-only; provide a CA or system trust for production).
- Backends configured for SCRAM-only auth are not supported unless GSS is enabled.
- On AuthenticationOk, links frontend/backend via `MessagePump` and emits audit via backend handler.

## Protocol Notes (Postgres)
- Frames: startup (length-prefixed, no type), then typed messages (type byte + length).
- Auth: no frontend auth; backend Kerberos only today. Listener TLS optional; SSLRequest/GSSENC are denied.
- Backend readiness: frontend frames are buffered until backend auth succeeds; backend audit handler observes server replies after auth.
- Cancel requests: not implemented in Java skeleton yet (exists in Teleport Go).

## Mongo Path (Current)
- Pipeline: `MongoFrameDecoder` (length-prefixed) → `MongoFrontendHandler`; `BackendConnector` dials backend with matching frame decoder and `BackendHandler`.
- No auth/TLS implemented; proxy is plaintext passthrough with optional `MongoRequestLogger` that dumps requests in hex.

## Cassandra Path (Current)
- Pipeline: `CassandraFrameDecoder` (DataStax native-protocol 1.5.0; supports v4 and modern v5/v6 segmented framing/CRC) → `CassandraFrontendHandler`; backend pipeline mirrors framing + `CassandraBackendHandler`.
- Proxy-terminated SASL/GSS (Kerberos): proxy answers backend `AUTHENTICATE`/`AUTH_CHALLENGE` with its own GSS tokens (ticket cache), ignores client `AUTH_RESPONSE`, forwards `AUTH_SUCCESS/READY` so clients stay unauthenticated. Kerberos config: `servicePrincipal`, `krb5ConfPath`, `krb5CcName`, `clientPrincipal`. Protocol version is passed through; compression is currently no-op (segments expected uncompressed).
- Modern framing: decoder switches layouts after AUTHENTICATE/READY similar to Teleport; supports segmented payloads. Failed handshake installs `CassandraFailedHandshakeHandler` which responds SUPPORTED → AUTHENTICATE → AUTH_ERROR.
- Audit: parsed Query/Prepare/Execute/Batch/Register emitted to `AuditRecorder`; `LoggingCassandraRequestLogger` still dumps headers/hex.
- Missing vs Teleport: listener/backend TLS, AWS SigV4 auth path, username validation hardening, compressed segments, and richer auth error paths.

## Extension Points / TODO
- Add TLS (server-only or mTLS) on the proxy listener.
- Implement PG cancel flow and fuller message coverage in `PostgresBackendAuditHandler`.
- Mongo/Cassandra: add structured command parsing/audit and compression awareness; add SigV4 and username validation parity with Teleport.
- Replace `LoggingAuditRecorder` with a Teleport-compatible emitter if integrating back to Teleport services.
- Optional prelude/agent (pamjit) to authenticate to the proxy before DB handshake.

## Parallels to Teleport’s Implementation
- Client side (Teleport tsh): opens a local TCP listener per database, authenticates to Teleport Proxy over mTLS using Teleport-issued client certs/ALPN/SNI, and forwards raw DB protocol. tsh does not parse Postgres/Mongo/Cassandra.
- Proxy side (Teleport Proxy): accepts DB protocol, authorizes Teleport identity, forwards startup to DB service over reverse tunnel, streams bytes; it does not handle target DB TLS.
- DB service side (Teleport engines): protocol-aware; parses startup, RBAC check, optional auto user provision, connects to actual DB using per-DB TLS config, sends protocol auth OK to the client, relays messages, emits audit events.
- Per-DB TLS (Teleport): `Auth.GetTLSConfig` builds a `tls.Config` per Database resource. Uses DB-specific CA (from resource or cloud roots), sets ServerName/mode (verify-full/verify-ca/insecure), and for on-prem generates a client cert from Teleport’s CA. TLS is applied per session when dialing the backend DB; clients remain unaware.

## Optional Local Agent
- A tsh/pamjit-style agent can still open localhost listeners and authenticate to the proxy using a prelude (JWT or Kerberos) before the DB handshake. The proxy does not require it today; it simply forwards DB protocol after completing backend auth on its own.

## Session Tracking (Current Java Proxy)
- Per connection: `Session` (ID, start time, client address, db/user/app from startup plus Teleport-like metadata: clusterName, hostId, databaseService/type/protocol, identityUser, autoCreateUserMode, databaseRoles, startupParameters, lockTargets, postgresPid, userAgent).
- `onSessionStart` on backend dial success/failure (PG) or READY/AUTH_SUCCESS (Cassandra); `onSessionEnd` on disconnect. PG/Mongo/Cassandra share the same audit surface.
