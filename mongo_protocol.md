# Mongo Protocol Handling (Teleport Reference)

Teleport’s MongoDB engine (see `teleport/lib/srv/db/mongodb/engine.go` and `protocol/*`) keeps the proxy thin: it parses messages for RBAC/audit, but it does not synthesize or cache Mongo responses. All handshake and heartbeat commands are forwarded to the backend; the only local mutation is adjusting `maxMessageSize` based on the server’s handshake reply.

## Message IDs and Correlation
- Mongo wire headers carry `requestID` and `responseTo`. Teleport preserves these end-to-end: the client’s `requestID` is sent unchanged to the backend, and the backend reply’s `responseTo` naturally matches the client `requestID`. There is no ID rewriting or caching, so correlation remains native to Mongo.
- For fire-and-forget messages (`MoreToCome`), no response is expected and no correlation is needed. When the server sets `MoreToCome`, Teleport drains all subsequent server messages (with their `responseTo` set) and forwards them in order to the client.
- Error path before first reply: if backend connection fails and no client message has been seen, Teleport waits for the first client message (usually `isMaster`/`hello`) and uses its `requestID` to frame a proper error reply via `protocol.ReplyError`, ensuring the client can match the error to its request.

## Handshake (`isMaster`/`hello`) and Heartbeats
- Clients typically start with `isMaster` (legacy) or `hello` (modern). `protocol.IsHandshake` flags these by command name (case-insensitive for `isMaster`).
- Engine flow:
  1) Authorize the command (denies auth-related commands like `authenticate`, `saslStart`, `saslContinue`, `logout`; otherwise checks DB/user via roles).
  2) Forward the raw message to the server (`serverConn.WriteWireMessage`) with the original `requestID`.
  3) If the message has `MoreToCome` set, return immediately (fire-and-forget semantics).
  4) Otherwise read the server reply (`protocol.ReadServerMessage`) and forward it to the client. If `MoreToCome` is set on the server’s message, keep draining and forwarding until cleared.
- Special handling of handshake replies: after reading the server’s handshake reply, `processHandshakeResponse` parses it with `description.NewServer` to learn `MaxMessageSize` and updates the engine’s limit. The reply itself is forwarded untouched; IDs stay intact.
- Heartbeat-style commands like `ping` are treated the same as any other command: authorized and forwarded; no local reply or caching.

## Caching / Local Responses
- Teleport does **not** cache `isMaster`/`hello`/heartbeat responses and does not serve them locally. Every command is forwarded to the backend unless RBAC denies it, in which case an error is sent instead of the backend reply.
- There is no local short-circuit for hello/heartbeats; backend availability and responses are always authoritative.

## Message Types & More-To-Come
- Supported opcodes include legacy `OP_QUERY`/`OP_REPLY` (still used by older `isMaster`), modern `OP_MSG`, and `OP_COMPRESSED`.
- `MoreToCome` flags (both client and server) are honored: if a client sends a fire-and-forget message, Teleport doesn’t await a reply; if the server indicates more responses, Teleport drains and forwards them all in order.

## Audit/Authorization Context
- Each client message is parsed to extract database/command for RBAC and audit; auth commands are blocked outright.
- Query audit logs the command string; results are just forwarded (no result caching or rewriting).

## Summary
- Handshake/heartbeat commands (`isMaster`/`hello`/`ping`) are forwarded; Teleport does not cache or locally answer them.
- Message IDs (`requestID`/`responseTo`) are preserved; errors before backend connect are framed against the first client `requestID`.
- The only handshake side-effect is updating `maxMessageSize` from the server’s reply.***
