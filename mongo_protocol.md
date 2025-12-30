# Mongo Protocol Handling (Teleport Reference)

Teleport’s MongoDB engine (see `teleport/lib/srv/db/mongodb/engine.go` and `protocol/*`) keeps the proxy transparent: it parses messages for RBAC/audit but does not synthesize or cache Mongo responses. Handshake/heartbeat commands are forwarded to the backend; the only side effect is updating `maxMessageSize` from the server’s handshake reply.

## Message IDs & Correlation
- Mongo wire headers carry `requestID` and `responseTo`. Teleport preserves them end-to-end—no rewriting. Client `requestID` goes to the backend; backend `responseTo` matches naturally.
- Fire-and-forget (`MoreToCome` on client) returns immediately. If the server sets `MoreToCome`, Teleport drains subsequent replies in order and forwards them.
- If backend connect fails before any client message, Teleport waits for the first client message (often `isMaster`/`hello`) and uses its `requestID` to frame an error via `protocol.ReplyError` so the client can correlate it.

## Handshake (`isMaster`/`hello`) & Heartbeats
- Detection: `protocol.IsHandshake` flags `isMaster` (case-insensitive) or `hello` (case-sensitive), works for `OP_MSG` and legacy `OP_QUERY`.
- Flow: authorize command → forward to server with original IDs → if `MoreToCome` on client, return; else read server reply, forward it, and if `MoreToCome` on server, drain all parts.
- Handshake response processing: `processHandshakeResponse` parses the server reply with `description.NewServer` and updates `maxMessageSize`; the reply itself is forwarded unchanged.
- Heartbeats like `ping` are treated like any other command: authorized and forwarded; no local replies or caching.

## Auth Commands Blocked
- Commands `authenticate`, `saslStart`, `saslContinue`, `logout` are denied (not forwarded). Auth is handled at TLS/x509 layer in Teleport; clients can’t re-auth mid-connection.

## No Caching / No Synthetic Replies
- Teleport never caches or locally answers `isMaster`/`hello`/heartbeat responses. Every allowed command goes to the backend; denied commands get an error instead of a backend reply.
- The only state derived from handshake is `maxMessageSize`; all other fields and replies pass through untouched.

## Wire Protocol Coverage & MoreToCome
- Supported opcodes: legacy `OP_QUERY/OP_REPLY` (old `isMaster`), modern `OP_MSG`, `OP_COMPRESSED`, plus legacy CRUD opcodes.
- `MoreToCome` honored both directions: client fire-and-forget doesn’t wait; server multi-part replies are fully forwarded.

## Error Path Before First Reply
- On backend connect errors with no prior client message, Teleport waits for the first client message to frame an error using that `requestID` so clients can parse it correctly. Then it sends the error and closes.

## Server Connection Handshake (No-Op)
- When Teleport dials Mongo, it uses a no-op handshaker so it doesn’t send its own metadata `isMaster`; otherwise the client’s handshake would be rejected because Mongo treats client metadata as immutable per connection.

## Summary
- Forward everything (except blocked auth commands); no caching/synthetic responses.
- Preserve `requestID/responseTo`; adjust `maxMessageSize` from server handshake.
- Honor `MoreToCome`; frame early errors against the first client `requestID`.***
