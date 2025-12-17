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
  - `PostgresProxyServer`: Netty server bootstrap; per-connection session, JWT validation, routing to backend host/port.
  - `PostgresFrameDecoder`: PG frame splitter (startup vs typed messages).
  - `PgMessages`: PG frontend parsing (Startup/Cancel/Query/Parse/Bind/Execute/Password/Terminate) and helpers (encode query, auth ok/cleartext, error response).
  - `FrontendHandler`: parses client messages, validates JWT (PasswordMessage), resolves backend, forwards frames, emits audit.
  - `PostgresBackendAuditHandler`: inspects backend CommandComplete/ErrorResponse → audit result.
  - `QueryLogger`/`LoggingQueryLogger`: query inspection/rewrite hooks.
- **mongo**: Length-prefixed framing, passthrough proxy with request logger.
  - `MongoProxyServer`, `MongoFrontendHandler`, `MongoFrameDecoder`, `MongoRequestLogger`/`LoggingMongoRequestLogger`.
- **cassandra**: Length-prefixed framing, passthrough proxy with request logger.
  - `CassandraProxyServer`, `CassandraFrontendHandler`, `CassandraFrameDecoder`, `CassandraRequestLogger`/`LoggingCassandraRequestLogger`.

## Current Auth Model (JWT Placeholder)
- PG: after StartupMessage, proxy challenges with `AuthenticationCleartextPassword`; treats `PasswordMessage` contents as JWT.
- `jwtValidator` (Config) decides accept/reject. Default is permissive; replace with real JWT verification (signature, exp, claims).
- On invalid JWT: sends PG `ErrorResponse` and closes.
- On success: records session start in audit, then connects to backend and forwards traffic.
- Mongo/Cassandra: currently only hex-logging; you can insert JWT validation before connecting.

## Routing Model
- `PostgresProxyServer.Config` exposes `TargetResolver` to choose backend host/port per connection, based on `DbSession` (user/db/app) and JWT string.
- Convenience `addRoute(databaseName, HostPort)` with `*` default. Example:
  ```java
  new PostgresProxyServer.Config()
      .addRoute("sales", new HostPort("pg-sales.internal", 5432))
      .addRoute("*", new HostPort("pg-default.internal", 5432));
  ```
- Modify `TargetResolver` to decode JWT claims for richer routing.

## Audit Hooks
- `AuditRecorder` mirrors Teleport semantics (`onSessionStart`, `onSessionEnd`, `onQuery`, `onResult`); default `LoggingAuditRecorder` logs JSON-like maps to SLF4J.
- PG: `FrontendHandler` emits `onSessionStart` on JWT success, `onQuery` for Query messages; `PostgresBackendAuditHandler` emits `onResult` on CommandComplete/ErrorResponse; `onSessionEnd` on disconnect.
- Extend/replace `LoggingAuditRecorder` to send events to a real sink.

## Build & Run
- Maven (`pom.xml`, Java 17, Netty 4.1.108.Final). Build:
  ```bash
  mvn -DskipTests package
  ```
  If ~/.m2 permissions are restricted, set an alternate repo: `mvn -Dmaven.repo.local=/tmp/m2 -DskipTests package`.
- Run PG proxy (example):
  ```java
  var cfg = new PostgresProxyServer.Config()
      .addRoute("*", new PostgresProxyServer.HostPort("127.0.0.1", 5432))
      .jwtValidator(token -> true); // TODO real JWT check
  new PostgresProxyServer(cfg).start();
  ```
  Connect with psql/JDBC/IntelliJ to proxy host/port, DB/user as normal; put JWT in password field.

## Protocol Notes (Postgres)
- Frames: startup (length-prefixed, no type), then typed messages (type byte + length).
- Auth: `AuthenticationCleartextPassword` prompt, accept `PasswordMessage` as JWT, then proceed (no TLS yet).
- Backend readiness: current code forwards client frames immediately; backend audit handler observes server replies.
- Cancel requests: not implemented in Java skeleton yet (exists in Teleport Go).

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
