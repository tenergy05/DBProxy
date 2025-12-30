# Teleport MongoDB Protocol Handling Analysis

This document analyzes how Teleport's MongoDB database access proxy handles special protocol frames, particularly `isMaster`/`hello` handshake commands and other client-server interactions.

## Executive Summary

| Aspect | Behavior |
|--------|----------|
| isMaster/hello forwarding | **Forwarded to backend** - not intercepted or cached |
| Response caching | **No caching** - every message round-trips to server |
| Handshake interception | **Response-only** - extracts `maxMessageSize` from server reply |
| Auth commands | **Blocked** - `authenticate`, `saslStart`, `saslContinue`, `logout` denied |
| Driver handshake | **Suppressed** - uses no-op handshaker to prevent metadata collision |

---

## 1. Message Flow Architecture

Teleport acts as a **transparent proxy** for MongoDB wire protocol messages. The core loop in `engine.go:121-130`:

```go
for {
    clientMessage, err := protocol.ReadMessage(e.clientConn, e.maxMessageSize)
    if err != nil {
        return trace.Wrap(err)
    }
    err = e.handleClientMessage(ctx, sessionCtx, clientMessage, e.clientConn, serverConn, msgFromClient, msgFromServer)
    if err != nil {
        return trace.Wrap(err)
    }
}
```

Every client message is:
1. Read from client connection
2. Authorized against RBAC rules
3. Forwarded to MongoDB server
4. Server response read and forwarded back to client

**There is no response caching whatsoever.**

---

## 2. isMaster/hello Handshake Handling

### 2.1 Detection Logic

Handshake commands are detected in `protocol/message.go:178-186`:

```go
const (
    IsMasterCommand = "isMaster"
    HelloCommand    = "hello"
)

func IsHandshake(m Message) bool {
    cmd, err := m.GetCommand()
    if err != nil {
        return false
    }
    // Servers must accept alternative casing for IsMasterCommand.
    return strings.EqualFold(cmd, IsMasterCommand) || cmd == HelloCommand
}
```

Key points:
- `isMaster` matching is **case-insensitive** (per MongoDB spec)
- `hello` matching is **case-sensitive**
- Detection works for both OP_MSG and legacy OP_QUERY formats

### 2.2 Forwarding Behavior

**isMaster/hello commands ARE forwarded to the backend server.** The only special handling is intercepting the **response** to extract server configuration.

From `engine.go:168-170`:

```go
// Intercept handshake server response to proper configure the engine.
if protocol.IsHandshake(clientMessage) {
    e.processHandshakeResponse(ctx, serverMessage)
}
```

### 2.3 Response Processing

The handshake response is processed in `engine.go:194-222` to extract `maxMessageSize`:

```go
func (e *Engine) processHandshakeResponse(ctx context.Context, respMessage protocol.Message) {
    var rawMessage bson.Raw
    switch resp := respMessage.(type) {
    case *protocol.MessageOpReply:
        // OP_REPLY for legacy handshake (deprecated MongoDB 5.0)
        if len(resp.Documents) == 0 {
            e.Log.WarnContext(ctx, "Empty MongoDB handshake response.")
            return
        }
        rawMessage = bson.Raw(resp.Documents[0])
    case *protocol.MessageOpMsg:
        // OP_MSG for modern handshake
        rawMessage = bson.Raw(resp.BodySection.Document)
    default:
        e.Log.WarnContext(ctx, "Unable to process MongoDB handshake response...")
        return
    }

    serverDescription := description.NewServer("", rawMessage)
    if serverDescription.MaxMessageSize > 0 {
        e.maxMessageSize = serverDescription.MaxMessageSize
    }
}
```

**Purpose**: Update the proxy's `maxMessageSize` to match the server's advertised limit. This prevents the proxy from rejecting messages the server would accept.

---

## 3. Heartbeat Handling

MongoDB clients send periodic `isMaster` or `hello` commands as heartbeats to:
- Monitor connection health
- Detect topology changes (replica set elections)
- Refresh server capabilities

**Teleport does NOT special-case heartbeats.** They follow the same path as any other command:
1. Forward to server
2. Extract `maxMessageSize` if handshake
3. Return response to client

There is no:
- Heartbeat caching
- Synthetic heartbeat responses
- Heartbeat coalescing/deduplication
- Connection keep-alive injection

---

## 4. Authentication Command Blocking

Certain commands are explicitly blocked in `engine.go:290-293`:

```go
switch command {
case "authenticate", "saslStart", "saslContinue", "logout":
    return trace.AccessDenied("access denied")
}
```

**Rationale**: Teleport handles authentication at the TLS/x509 layer. Clients must not be allowed to:
- Re-authenticate as a different user
- Start SASL authentication flows
- Log out (which would break the session)

These commands return `access denied` without being forwarded to the server.

---

## 5. Server Connection Handshake (No-Op Pattern)

When Teleport establishes its own connection to the MongoDB server, it uses a **no-op handshaker** to prevent protocol conflicts.

From `connect.go:265-280`:

```go
// handshaker is Mongo driver no-op handshaker that doesn't send client
// metadata when connecting to server.
type handshaker struct{}

// GetHandshakeInformation overrides default auth handshaker's logic which
// would otherwise have sent client metadata request to the server as a first
// message. Otherwise, the actual client connecting to Teleport will get an
// error when they try to send its own metadata since client metadata is
// immutable.
func (h *handshaker) GetHandshakeInformation(context.Context, address.Address, driver.Connection) (driver.HandshakeInformation, error) {
    return driver.HandshakeInformation{}, nil
}

func (h *handshaker) FinishHandshake(context.Context, driver.Connection) error {
    return nil
}
```

**Why this matters**:
- MongoDB servers store client metadata from the first handshake (immutable for connection lifetime)
- If Teleport's driver sent its own `isMaster` with metadata, the real client's subsequent `isMaster` would fail
- The no-op handshaker allows the client's handshake to be the first one the server sees

---

## 6. Wire Protocol Message Types

Teleport handles these MongoDB wire protocol opcodes (`protocol/message.go:56-68`):

| OpCode | Name | Usage |
|--------|------|-------|
| 2001 | OP_REPLY | Legacy server response |
| 2002 | OP_UPDATE | Legacy update (deprecated) |
| 2003 | OP_INSERT | Legacy insert (deprecated) |
| 2004 | OP_QUERY | Legacy query/isMaster (deprecated) |
| 2005 | OP_GETMORE | Legacy cursor fetch (deprecated) |
| 2006 | OP_DELETE | Legacy delete (deprecated) |
| 2007 | OP_KILLCURSORS | Close cursor |
| 2010 | OP_COMMAND | Internal command (deprecated) |
| 2011 | OP_COMMANDREPLY | Internal response (deprecated) |
| 2012 | OP_COMPRESSED | Compressed message wrapper |
| 2013 | OP_MSG | Modern extensible message |

Modern clients use **OP_MSG** (2013) for all operations including `isMaster`/`hello`. Legacy clients may use **OP_QUERY** (2004).

---

## 7. MoreToCome Flag Handling

MongoDB supports streaming responses via the `moreToCome` flag. Teleport handles this in `engine.go:157-188`:

```go
// Some client messages will not receive a reply.
if clientMessage.MoreToCome(nil) {
    return nil
}
// ...read and forward response...

// Keep reading if server indicated it has more to send.
for serverMessage.MoreToCome(clientMessage) {
    serverMessage, err = protocol.ReadServerMessage(ctx, serverConn, e.maxMessageSize)
    // ...forward to client...
}
```

This ensures:
- Fire-and-forget client messages (exhaust cursors) don't block waiting for response
- Multi-part server responses are fully forwarded

---

## 8. Topology/Replica Set Handling

Teleport uses the MongoDB driver's topology package to handle replica set connections (`connect.go:67-75`):

```go
top, err := topology.New(options)
if err != nil {
    return nil, nil, trace.Wrap(err)
}
if err := top.Connect(); err != nil {
    e.Log.DebugContext(e.Context, "Failed to connect topology", "error", err)
    return nil, nil, trace.Wrap(err)
}
conn, err := e.selectServerConn(ctx, top, selector)
```

Server selection respects the `readPreference` from the connection string (`connect.go:254-263`):

```go
func getServerSelector(clientOptions *options.ClientOptions) (description.ServerSelector, error) {
    if clientOptions.ReadPreference == nil {
        return description.ReadPrefSelector(readpref.Primary()), nil
    }
    readPref, err := readpref.New(clientOptions.ReadPreference.Mode())
    if err != nil {
        return nil, trace.Wrap(err)
    }
    return description.ReadPrefSelector(readPref), nil
}
```

**Note**: The topology discovery happens at connection time, not per-request. Once connected to a server, all client messages go to that server.

---

## 9. Error Handling and Client Notification

When errors occur before server connection is established, Teleport waits for a client message before sending an error response (`engine.go:318-343`):

```go
func (e *Engine) replyError(clientConn net.Conn, replyTo protocol.Message, err error) {
    // If an error happens during server connection, wait for a client message
    // before replying to ensure the client can interpret the reply.
    // The first message is usually the isMaster hello message.
    if replyTo == nil && !e.serverConnected {
        waitChan := make(chan protocol.Message, 1)
        go func() {
            waitChan <- e.waitForAnyClientMessage(clientConn)
        }()

        select {
        case clientMessage := <-waitChan:
            replyTo = clientMessage
        case <-e.Clock.After(common.DefaultMongoDBServerSelectionTimeout):
            // timeout handling
        }
    }
    // ... send error
}
```

This ensures the client receives a properly formatted error response matching its request.

---

## 10. Summary: Design Philosophy

Teleport's MongoDB proxy follows a **transparent forwarding** design:

1. **No caching**: Every request goes to the server, every response comes from the server
2. **Minimal interception**: Only handshake responses are inspected (for `maxMessageSize`)
3. **Strict auth boundary**: Auth commands blocked; authentication handled at TLS layer
4. **Driver isolation**: No-op handshaker prevents metadata conflicts
5. **Full protocol support**: All wire protocol message types handled

This design prioritizes **correctness and transparency** over performance optimizations like response caching. Clients see exactly what the server sends, with no synthetic or cached responses.

---

## Appendix: File References

| File | Purpose |
|------|---------|
| `lib/srv/db/mongodb/engine.go` | Main proxy engine, message loop, RBAC |
| `lib/srv/db/mongodb/connect.go` | Server connection, topology, handshaker |
| `lib/srv/db/mongodb/protocol/message.go` | Wire protocol parsing, IsHandshake() |
| `lib/srv/db/mongodb/protocol/opmsg.go` | OP_MSG message handling |
| `lib/srv/db/mongodb/protocol/opquery.go` | OP_QUERY message handling |
| `lib/srv/db/mongodb/protocol/opreply.go` | OP_REPLY message handling |
