# DBProxy Architecture (Java/Netty Prototype)

## Goal
Teleport-style (v18.5.1) database proxy in Java that preserves native wire protocols (Postgres, Mongo, Cassandra) with Teleport-like naming/layout. Clients (psql/JDBC/IntelliJ, mongo shell/driver, cqlsh/driver) remain unaware of auth; the proxy completes SASL/GSS (Kerberos) to the backend using its own credentials.

Protocol selection is determined by the listener/engine or routing metadata, not by sniffing bytes.

## Top-Level Structure
- **core**: DB-agnostic plumbing.
  - `BackendConnector`: dials backend using frontend event loop with a supplied pipeline initializer.
  - `BackendHandler`: streams backend -> frontend.
  - `MessagePump`: ties lifecycles and flush-closes channels.
  - `audit/*`: `AuditRecorder`, `Session`, `Query`, `Result`, `LoggingAuditRecorder`.
- **postgres**: Postgres (PG)-specific framing, parsing, proxy server.
  - `PostgresEngine`: Netty server bootstrap; per-connection session metadata and routing to backend host/port; optional listener TLS via Netty `SslHandler`.
  - `PostgresFrameDecoder`: PG frame splitter (startup vs typed messages). Public so other packages can reuse in pipelines.
  - `PgMessages`: PG frontend parsing (Startup/Cancel/Query/Parse/Bind/Execute/Password/Terminate) and helpers (encode query, auth ok/cleartext, error response).
  - `FrontendHandler`: parses client messages, ignores client passwords (proxy owns backend auth), resolves backend, forwards frames, emits audit.
  - `PostgresBackendAuditHandler`: inspects backend CommandComplete/ErrorResponse -> audit result. Public ctor for cross-package pipeline wiring.
  - `QueryLogger`/`LoggingQueryLogger`: query inspection/rewrite hooks.
  - `postgres.auth.PgGssBackend`: TLS + GSSAPI (Generic Security Services API, Kerberos) backend connector; builds a backend pipeline with SSL, frame decoder, audit, and a GSS handshake handler.
- **mongo**: Length-prefixed framing, passthrough proxy with request logger.
  - `MongoEngine`, `MongoFrontendHandler`, `MongoFrameDecoder`, `MongoRequestLogger`/`LoggingMongoRequestLogger`.
- **cassandra**: Cassandra-native framing (v3-v6; modern segments) engine with proxy-terminated SASL/GSS (Kerberos) to the backend; client sees a ready session without supplying credentials. Request logger can parse queries for audit-style logs.
  - `CassandraEngine` (listener), `CassandraFrontendHandler`, `CassandraBackendHandler`, `CassandraFrameDecoder`, `CassandraHandshakeState`, `CassandraGssAuthenticator`, `CassandraFailedHandshakeHandler`, `CassandraRequestLogger`/`LoggingCassandraRequestLogger`.

---

## Current Auth Model (proxy-owned)
- **Postgres**: frontend never authenticates clients. `PasswordMessage` is dropped; the proxy authenticates to the backend with Kerberos via `PgGssBackend` using route/service principal/krb5 settings. `onSessionStart` fires once backend dial/auth completes (success or error).
- **Cassandra**: client `AUTH_RESPONSE` is parsed for username validation (like Teleport's `validateUsername`), but credentials are ignored. The proxy answers backend `AUTHENTICATE`/`AUTH_CHALLENGE` with its own GSS tokens (`CassandraGssAuthenticator`), forwards `AUTH_SUCCESS/READY`, and clients see a ready session without supplying credentials. Failed handshakes install `CassandraFailedHandshakeHandler` that replies SUPPORTED -> AUTHENTICATE -> AUTH_ERROR before closing.
- **Mongo**: passthrough with no auth.
- Prelude/agent (pamjit/tsh equivalent) can be layered later to authenticate/authorize to the proxy; the proxy already mirrors Teleport engine naming/layout and assumes identity is established upstream.

---

## Cassandra Implementation (Detailed)

### Architecture Overview
The Cassandra proxy follows Teleport's architecture closely:

```
+----------+      No Auth       +---------------+    Kerberos/SASL    +-----------+
|  Client  | -----------------> | Cassandra     | -----------------> | Cassandra |
|  (cqlsh) |   AUTH_RESPONSE    |    Proxy      |   AUTH_RESPONSE    |  Backend  |
|          |   validated but    |    (Java)     |   with GSS token   |           |
+----------+   credentials      +---------------+                    +-----------+
               ignored                 |
                                       +-- Audit: QUERY/PREPARE/EXECUTE/BATCH
                                       +-- Session: start/end
```

### Key Components

#### CassandraFrameDecoder
Handles protocol framing with Teleport-matching features:

| Feature | Implementation |
|---------|---------------|
| **Separate read/write framing** | `modernFramingRead` / `modernFramingWrite` flags allow client v5 / server v4 mismatch |
| **Compression support** | LZ4 and Snappy via `updateCompression(String)` after parsing STARTUP |
| **Modern framing switch** | Triggered on READY/AUTHENTICATE (like Teleport's `maybeSwitchToModernLayout`) |
| **Protocol versions** | Supports v3, v4, v5, v6 with automatic segment handling |

#### CassandraHandshakeState
Tracks per-connection state during handshake:

- **Compression**: Captured from STARTUP, applied to both frontend/backend decoders
- **Username validation**: `validateClientAuth()` validates client username matches expected session user (like Teleport)
- **Driver info**: Captures DRIVER_NAME/DRIVER_VERSION for session/audit
- **Protocol version**: Tracks negotiated version for correct framing
- **GSS authenticator**: Manages Kerberos token generation

#### CassandraFrontendHandler
Handles client-side protocol:

1. **OPTIONS**: Forwarded to backend
2. **STARTUP**: Parsed to extract compression, driver info; forwarded to backend
3. **AUTH_RESPONSE**:
   - Parsed to extract username/password (PasswordAuthenticator format)
   - Username validated if `validateUsername` config is enabled
   - Credentials ignored - proxy handles backend auth
   - Auth error sent if validation fails

#### CassandraBackendHandler
Handles backend-side protocol:

1. **AUTHENTICATE**: Triggers GSS handshake, switches to modern framing
2. **AUTH_CHALLENGE**: Continues GSS challenge-response
3. **AUTH_SUCCESS**: Marks session ready, flushes pending
4. **READY**: Marks session ready (no-auth case)
5. **ERROR**: Forwarded to client, connection closed

### Protocol Flow (SASL/GSS)

```
Client -> Proxy: OPTIONS
Proxy -> Backend: OPTIONS
Backend -> Proxy: SUPPORTED
Proxy -> Client: SUPPORTED

Client -> Proxy: STARTUP (compression, driver info captured)
Proxy -> Backend: STARTUP

Backend -> Proxy: AUTHENTICATE
Proxy -> Client: AUTHENTICATE
Proxy -> Backend: AUTH_RESPONSE (GSS initial token)

Backend -> Proxy: AUTH_CHALLENGE
Proxy -> Client: AUTH_CHALLENGE
Client -> Proxy: AUTH_RESPONSE (validated, ignored)
Proxy -> Backend: AUTH_RESPONSE (GSS response token)

... repeat AUTH_CHALLENGE/RESPONSE until ...

Backend -> Proxy: AUTH_SUCCESS
Proxy -> Client: AUTH_SUCCESS
-- Session Ready --
```

### Configuration Options

```java
CassandraEngine.Config config = new CassandraEngine.Config()
    .listenHost("0.0.0.0")
    .listenPort(19042)
    .targetHost("cassandra.example.com")
    .targetPort(9042)
    // Kerberos settings
    .servicePrincipal("cassandra/cassandra.example.com")
    .krb5ConfPath("/etc/krb5.conf")
    .krb5CcName("/tmp/krb5cc_proxy")
    .clientPrincipal("proxy@EXAMPLE.COM")
    // Username validation (like Teleport)
    .expectedUsername("allowed_user")
    .validateUsername(true);
```

### Robustness Features

| Feature | Description |
|---------|-------------|
| **Multi-version support** | v3-v6 clients handled correctly |
| **Compression passthrough** | LZ4/Snappy decompressed for parsing, forwarded as-is |
| **Stream ID preservation** | All responses use correct client stream ID |
| **Version mismatch handling** | Separate read/write framing modes |
| **Failed handshake** | Protocol-compliant error responses |
| **Backend connect failure** | Proper error propagation to client |

---

## Postgres Implementation (Detailed)

### Architecture Overview

```
+----------+      No Auth       +---------------+    Kerberos/GSSAPI  +-----------+
|  Client  | -----------------> |   Postgres    | -----------------> | Postgres  |
|  (psql)  |   PasswordMessage  |    Proxy      |   GSS tokens       |  Backend  |
|          |   dropped          |    (Java)     |                    |           |
+----------+                    +---------------+                    +-----------+
                                       |
                                       +-- Audit: QUERY/PREPARE/EXECUTE
                                       +-- Result: CommandComplete/Error
```

### Key Components

#### PostgresFrameDecoder
- Handles startup frame (length-prefixed, no type byte)
- Handles regular frames (type byte + length)
- State machine for startup vs post-startup

#### FrontendHandler
- Parses StartupMessage for user/database/application_name
- Drops PasswordMessage (proxy owns auth)
- Forwards Query/Parse/Bind/Execute with audit hooks
- Buffers frames until backend connected

#### PgGssBackend
- Builds TLS connection (client mode, TLS 1.2/1.3)
- Handles AuthenticationGSS/GSSContinue handshake
- Uses JGSS with ticket cache (`useTicketCache=true`)
- Configurable: servicePrincipal, krb5ConfPath, krb5CcName, clientPrincipal

#### PostgresBackendAuditHandler
- Inspects CommandComplete -> `onResult` with rows affected
- Inspects ErrorResponse -> `onResult` with error message
- Forwards all frames to frontend

### Configuration

```java
PostgresEngine.Config config = new PostgresEngine.Config()
    .listenHost("0.0.0.0")
    .listenPort(15432)
    .addRoute("*", new PostgresEngine.Route(
        "pg.example.com", 5432,
        "db_user", "database_name",
        "/etc/ssl/pg-ca.pem", "pg.example.com",
        "/tmp/krb5cc_proxy", "/etc/krb5.conf",
        "proxy@EXAMPLE.COM", "postgres/pg.example.com"
    ));
```

---

## Mongo Implementation (Detailed)

### Architecture Overview
Simple passthrough proxy with length-prefixed framing:

```
+----------+                    +---------------+                    +-----------+
|  Client  | -----------------> |    Mongo      | -----------------> |   Mongo   |
|  (shell) |   passthrough      |    Proxy      |   passthrough      |  Backend  |
+----------+                    +---------------+                    +-----------+
                                       |
                                       +-- Request logging (hex dump)
```

### Components
- `MongoFrameDecoder`: int32 length-prefixed framing
- `MongoFrontendHandler`: connects backend, forwards messages
- `MongoRequestLogger`: optional hex dump of requests

### Missing vs Cassandra/Postgres
- No authentication (passthrough)
- No structured audit events
- No TLS support
- No message parsing beyond framing

---

## Audit System

### AuditRecorder Interface
```java
public interface AuditRecorder {
    Session newSession(SocketAddress clientAddress);
    void onSessionStart(Session session, Throwable error);
    void onSessionEnd(Session session);
    void onQuery(Session session, Query query);
    void onResult(Session session, Result result);
}
```

### Session Fields (Teleport-compatible)
- `id`, `startTime`, `clientAddress`
- `databaseUser`, `databaseName`, `applicationName`
- `protocol`, `databaseType`, `databaseProtocol`, `databaseService`
- `clusterName`, `hostId`, `identityUser`
- `autoCreateUserMode`, `databaseRoles`, `startupParameters`
- `lockTargets`, `postgresPid`, `userAgent`

### Audit Events by Protocol

| Protocol | SessionStart | Query | Result | SessionEnd |
|----------|--------------|-------|--------|------------|
| Postgres | Backend auth complete/fail | Query message | CommandComplete/Error | Disconnect |
| Cassandra | READY/AUTH_SUCCESS | QUERY/PREPARE/EXECUTE/BATCH/REGISTER | - | Disconnect |
| Mongo | - | - | - | - |

---

## Build & Run

### Build
```bash
mvn -DskipTests package
```

### Run Postgres Proxy
```bash
java -jar target/dbproxy-0.1.0-SNAPSHOT.jar /path/to/config.json
```

### Run Cassandra Proxy
```bash
java -cp target/dbproxy-0.1.0-SNAPSHOT.jar com.poc.pamport.cassandra.CassandraEngine
```

### Run Mongo Proxy
```bash
java -cp target/dbproxy-0.1.0-SNAPSHOT.jar com.poc.pamport.mongo.MongoEngine
```

---

## Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
        <version>4.1.108.Final</version>
    </dependency>
    <dependency>
        <groupId>com.datastax.oss</groupId>
        <artifactId>native-protocol</artifactId>
        <version>1.5.0</version>
    </dependency>
    <dependency>
        <groupId>org.lz4</groupId>
        <artifactId>lz4-java</artifactId>
        <version>1.8.0</version>
    </dependency>
    <dependency>
        <groupId>org.xerial.snappy</groupId>
        <artifactId>snappy-java</artifactId>
        <version>1.1.10.5</version>
    </dependency>
    <!-- SLF4J, Jackson for config -->
</dependencies>
```

---

## Gaps vs Teleport Go Implementation

### Cassandra
| Feature | Teleport | Java Proxy |
|---------|----------|------------|
| Protocol versions | v3-v6 | v3-v6 |
| Modern framing | Full | Full |
| Compression | LZ4/Snappy | LZ4/Snappy |
| Username validation | Yes | Yes |
| AWS SigV4 auth | Yes | No |
| TLS to backend | Yes | No |
| TLS to frontend | Yes | No |

### Postgres
| Feature | Teleport | Java Proxy |
|---------|----------|------------|
| GSS auth | Yes | Yes |
| SCRAM/MD5 auth | Yes | No |
| Cancel flow | Yes | No |
| SSL negotiation | Full | Deny only |
| Statement tracking | Yes | No |

### Mongo
| Feature | Teleport | Java Proxy |
|---------|----------|------------|
| Auth | SCRAM-SHA-256 | No |
| TLS | Yes | No |
| Command parsing | Yes | No |
| Audit events | Yes | No |

---

## Extension Points / TODO

1. **TLS Support**: Add server-side TLS for frontend connections, client-side TLS for Cassandra/Mongo backends
2. **AWS SigV4**: Implement for AWS Keyspaces (Cassandra)
3. **Mongo Auth**: Add SCRAM-SHA-256 authentication
4. **Postgres Cancel**: Implement PID/secret-based cancel routing
5. **Postgres SCRAM**: Add SCRAM-SHA-256 backend auth option
6. **Structured Mongo Audit**: Parse MongoDB commands for audit events
7. **Result Auditing**: Parse Cassandra RESULT frames for audit
8. **RBAC Integration**: Connect to external authorization service

---

## Parallels to Teleport's Implementation

### Client side (Teleport tsh)
Opens a local TCP listener per database, authenticates to Teleport Proxy over mTLS using Teleport-issued client certs/ALPN/SNI, and forwards raw DB protocol. tsh does not parse Postgres/Mongo/Cassandra.

### Proxy side (Teleport Proxy)
Accepts DB protocol, authorizes Teleport identity, forwards startup to DB service over reverse tunnel, streams bytes; it does not handle target DB TLS.

### DB service side (Teleport engines)
Protocol-aware; parses startup, RBAC check, optional auto user provision, connects to actual DB using per-DB TLS config, sends protocol auth OK to the client, relays messages, emits audit events.

### This Java Proxy
Acts like Teleport's DB service side - protocol-aware, handles backend auth, emits audit. Can be deployed as a standalone proxy or integrated with an external auth/authz layer.

---

## Session Tracking (Current Java Proxy)

Per connection: `Session` with:
- ID, start time, client address
- db/user/app from startup
- Teleport-like metadata: clusterName, hostId, databaseService/type/protocol, identityUser, autoCreateUserMode, databaseRoles, startupParameters, lockTargets, postgresPid, userAgent

Lifecycle:
- `onSessionStart` on backend auth complete (PG) or READY/AUTH_SUCCESS (Cassandra)
- `onSessionEnd` on disconnect
- PG/Mongo/Cassandra share the same audit surface
