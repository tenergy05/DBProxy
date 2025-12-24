package com.poc.pamport.cassandra;

import com.poc.pamport.core.audit.AuditRecorder;
import com.poc.pamport.core.audit.Session;
import com.poc.pamport.core.audit.Query;
import com.poc.pamport.cassandra.protocol.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks a single Cassandra frontend/backend pair during handshake.
 *
 * Enhanced to match Teleport's approach:
 * - Tracks compression negotiated in STARTUP
 * - Validates username from client's AUTH_RESPONSE
 * - Supports separate frontend/backend frame decoders for version mismatch
 */
final class CassandraHandshakeState {
    private static final Logger log = LoggerFactory.getLogger(CassandraHandshakeState.class);

    private final CassandraEngine.Config config;
    private final CassandraGssAuthenticator gss;
    private final CassandraRequestLogger requestLogger;
    private final AuditRecorder auditRecorder;
    private Channel frontend;
    private Channel backend;
    private volatile boolean ready;
    private int protocolVersion = -1;
    private final List<ByteBuf> pending = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Session session;
    private boolean sessionStarted;

    // Enhanced tracking for robust client handling
    private String compression;           // Negotiated compression from STARTUP
    private String expectedUsername;      // Expected username for validation (from config or session)
    private String clientUsername;        // Username from client's AUTH_RESPONSE
    private String clientDriverName;      // Driver name from STARTUP
    private String clientDriverVersion;   // Driver version from STARTUP
    private boolean usernameValidated;    // Whether username was validated

    CassandraHandshakeState(CassandraEngine.Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.gss = new CassandraGssAuthenticator(config);
        this.requestLogger = config.requestLogger;
        this.auditRecorder = config.auditRecorder;
        this.expectedUsername = config.expectedUsername; // May be null if no validation required
    }

    void frontend(Channel channel) {
        this.frontend = channel;
    }

    Channel frontend() {
        return frontend;
    }

    void backend(Channel channel) {
        this.backend = channel;
    }

    Channel backend() {
        return backend;
    }

    CassandraGssAuthenticator gss() {
        return gss;
    }

    boolean isReady() {
        return ready;
    }

    void markReady() {
        this.ready = true;
        startSession(null);
    }

    void ensureProtocolVersion(int version) {
        if (protocolVersion == -1) {
            protocolVersion = version;
        }
    }

    int protocolVersion() {
        return protocolVersion == -1 ? 4 : protocolVersion;
    }

    // ---- Compression handling ----

    /**
     * Called when STARTUP is parsed to capture compression setting.
     */
    void setCompression(String compression) {
        this.compression = compression;
        // Update frontend decoder
        CassandraFrameDecoder frontendDecoder = getFrontendDecoder();
        if (frontendDecoder != null && compression != null) {
            frontendDecoder.updateCompression(compression);
        }
    }

    String getCompression() {
        return compression;
    }

    /**
     * Update backend decoder compression after we've seen STARTUP.
     */
    void updateBackendCompression() {
        if (compression != null && backend != null) {
            CassandraFrameDecoder backendDecoder = getBackendDecoder();
            if (backendDecoder != null) {
                backendDecoder.updateCompression(compression);
            }
        }
    }

    // ---- Username validation (like Teleport) ----

    /**
     * Set the expected username for validation.
     * Called from config or when session user is established.
     */
    void setExpectedUsername(String username) {
        this.expectedUsername = username;
    }

    /**
     * Called when client's AUTH_RESPONSE is parsed.
     * Returns error message if validation fails, null if ok.
     */
    String validateClientAuth(Protocol.AuthResponseMessage authResponse) {
        if (authResponse == null || !authResponse.hasCredentials()) {
            // No credentials in auth response - might be GSS or other authenticator
            // We allow this since proxy handles backend auth anyway
            log.debug("Client AUTH_RESPONSE has no password credentials");
            return null;
        }

        this.clientUsername = authResponse.username();

        // If session has a database user set, use that for validation
        if (session != null && session.getDatabaseUser() != null) {
            expectedUsername = session.getDatabaseUser();
        }

        // Validate username if we have an expected value
        if (expectedUsername != null && !expectedUsername.isEmpty()) {
            if (!expectedUsername.equals(clientUsername)) {
                String error = String.format(
                    "cassandra user %q doesn't match expected username %q",
                    clientUsername, expectedUsername);
                log.warn("Username validation failed: {}", error);
                return error;
            }
            log.debug("Username validated: {}", clientUsername);
        }

        usernameValidated = true;

        // Update session with validated username
        if (session != null && clientUsername != null) {
            session.setDatabaseUser(clientUsername);
        }

        return null; // Validation passed
    }

    String getClientUsername() {
        return clientUsername;
    }

    boolean isUsernameValidated() {
        return usernameValidated;
    }

    // ---- Driver info from STARTUP ----

    void setDriverInfo(String name, String version) {
        this.clientDriverName = name;
        this.clientDriverVersion = version;
        if (session != null) {
            String userAgent = (name != null ? name : "unknown") +
                (version != null ? "/" + version : "");
            session.setUserAgent(userAgent);
        }
    }

    String getClientDriverName() {
        return clientDriverName;
    }

    String getClientDriverVersion() {
        return clientDriverVersion;
    }

    // ---- Framing mode switching (like Teleport) ----

    /**
     * Switch frontend decoder to modern framing after READY/AUTHENTICATE.
     */
    void switchFrontendToModernFraming(int version) {
        CassandraFrameDecoder decoder = getFrontendDecoder();
        if (decoder != null) {
            decoder.switchToModernFramingRead(version);
            decoder.switchToModernFramingWrite(version);
        }
    }

    /**
     * Switch backend decoder to modern framing after READY/AUTHENTICATE.
     */
    void switchBackendToModernFraming(int version) {
        CassandraFrameDecoder decoder = getBackendDecoder();
        if (decoder != null) {
            decoder.switchToModernFramingRead(version);
            decoder.switchToModernFramingWrite(version);
        }
    }

    private CassandraFrameDecoder getFrontendDecoder() {
        if (frontend == null) return null;
        return (CassandraFrameDecoder) frontend.pipeline().get("cassandraFrameDecoder");
    }

    private CassandraFrameDecoder getBackendDecoder() {
        if (backend == null) return null;
        return (CassandraFrameDecoder) backend.pipeline().get("cassandraFrameDecoder");
    }

    // ---- Pending message handling ----

    void addPending(ByteBuf buf) {
        pending.add(buf.retain());
    }

    void flushPending() {
        Channel ch = backend;
        if (ch == null) {
            return;
        }
        for (ByteBuf buf : pending) {
            ch.write(buf);
        }
        pending.clear();
        ch.flush();
    }

    void releasePending() {
        for (ByteBuf buf : pending) {
            ReferenceCountUtil.safeRelease(buf);
        }
        pending.clear();
    }

    // ---- Message forwarding ----

    void forwardToFrontend(ByteBuf msg) {
        Channel ch = frontend;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(msg.retain());
        } else {
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    void forwardToBackend(ByteBuf msg) {
        Channel ch = backend;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(msg.retain());
        } else {
            // Backend not ready yet, buffer the message
            addPending(msg);
        }
    }

    // ---- Connection management ----

    void closeBoth() {
        if (closed.compareAndSet(false, true)) {
            if (frontend != null) {
                frontend.close();
            }
            if (backend != null) {
                backend.close();
            }
            releasePending();
            endSession();
        }
    }

    // ---- Auth response sending ----

    void sendAuthResponse(ChannelHandlerContext ctx, Protocol.Header header, byte[] token) {
        ByteBuf buf = ctx.alloc().buffer(Protocol.HEADER_LENGTH + Integer.BYTES + token.length);
        byte versionByte = (byte) (protocolVersion() & 0x7F); // request direction
        buf.writeByte(versionByte);
        buf.writeByte(0); // flags
        buf.writeShort(header.streamId());
        buf.writeByte(Protocol.OPCODE_AUTH_RESPONSE);
        buf.writeInt(Integer.BYTES + token.length);
        buf.writeInt(token.length);
        buf.writeBytes(token);
        ctx.writeAndFlush(buf);
    }

    /**
     * Send authentication error to client.
     */
    void sendAuthError(ChannelHandlerContext ctx, Protocol.Header header, String message) {
        byte[] msgBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int bodyLen = Integer.BYTES + Short.BYTES + msgBytes.length;
        ByteBuf buf = ctx.alloc().buffer(Protocol.HEADER_LENGTH + bodyLen);
        buf.writeByte((byte) (0x80 | protocolVersion())); // response direction
        buf.writeByte(0); // flags
        buf.writeShort(header.streamId());
        buf.writeByte(Protocol.OPCODE_ERROR);
        buf.writeInt(bodyLen);
        buf.writeInt(0x0100); // AUTH_ERROR code
        buf.writeShort(msgBytes.length);
        buf.writeBytes(msgBytes);
        ctx.writeAndFlush(buf).addListener(f -> ctx.close());
    }

    // ---- Session management ----

    void session(Session session) {
        this.session = session;
    }

    Session session() {
        return session;
    }

    void startSession(Throwable error) {
        if (sessionStarted || session == null) {
            return;
        }
        sessionStarted = true;
        auditRecorder.onSessionStart(session, error);
    }

    void endSession() {
        if (sessionStarted && session != null) {
            auditRecorder.onSessionEnd(session);
        }
    }

    void onQuery(Protocol.ParsedMessage parsed) {
        if (parsed == null || session == null) {
            return;
        }
        auditRecorder.onQuery(session, Query.of(parsed.detail()));
    }

    // ---- Error handling ----

    void fail(Throwable error) {
        startSession(error);
        Channel fe = frontend;
        if (fe != null && fe.isActive()) {
            try {
                fe.pipeline().addAfter("cassandraFrameDecoder", "failedHandshake",
                    new CassandraFailedHandshakeHandler(error == null ? null : error.getMessage()));
            } catch (Exception e) {
                log.warn("Failed to install failed-handshake handler", e);
            }
        }
        closeBoth();
    }

    /**
     * Fail with a specific error sent to the client before closing.
     */
    void failWithError(String errorMessage) {
        startSession(new IllegalStateException(errorMessage));
        closeBoth();
    }
}
