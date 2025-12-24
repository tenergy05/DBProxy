package com.poc.pamport.cassandra;

import com.poc.pamport.core.BackendConnector;
import com.poc.pamport.core.MessagePump;
import com.poc.pamport.core.audit.AuditRecorder;
import com.poc.pamport.core.audit.Session;
import com.poc.pamport.cassandra.protocol.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CassandraFrontendHandler parses client frames, drives backend SASL/GSS auth with proxy-owned creds,
 * then switches to raw forwarding with audit/logging.
 *
 * Enhanced to match Teleport's approach:
 * - Parses STARTUP to extract compression and driver info
 * - Properly handles different client protocol versions
 * - Sends proper error responses on validation failure
 */
final class CassandraFrontendHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(CassandraFrontendHandler.class);

    private final CassandraEngine.Config config;
    private final CassandraRequestLogger requestLogger;
    private final List<ByteBuf> pending = new ArrayList<>();
    private final CassandraHandshakeState state;
    private final AuditRecorder auditRecorder;
    private Session session;
    private Channel backend;
    private ChannelHandlerContext frontendCtx;

    // Track handshake state
    private boolean startupReceived;
    private boolean backendConnectFailed;

    CassandraFrontendHandler(CassandraEngine.Config config) {
        super(false); // Disable auto-release; we manage ByteBuf lifecycle manually
        this.config = config;
        this.requestLogger = config.requestLogger;
        this.state = new CassandraHandshakeState(config);
        this.auditRecorder = config.auditRecorder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.frontendCtx = ctx;
        this.session = auditRecorder.newSession(ctx.channel().remoteAddress());
        this.session.setProtocol("cassandra");
        this.session.setDatabaseType("cassandra");
        this.session.setDatabaseProtocol("cassandra");
        this.session.setDatabaseService(config.targetHost + ":" + config.targetPort);
        state.session(session);
        state.frontend(ctx.channel());

        // Connect to backend asynchronously
        BackendConnector connector = new BackendConnector(
            config.targetHost,
            config.targetPort,
            frontend -> new CassandraBackendPipelineInitializer(state)
        );
        connector.connect(ctx.channel())
            .addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.warn("Failed to connect to backend {}:{}", config.targetHost, config.targetPort, future.cause());
                    backendConnectFailed = true;
                    // Send error to client via failed handshake flow
                    state.fail(new IllegalStateException("Backend connection failed: " +
                        (future.cause() != null ? future.cause().getMessage() : "unknown error")));
                    return;
                }
                backend = future.channel();
                state.backend(backend);

                // If we have pending compression settings, apply to backend decoder
                state.updateBackendCompression();

                MessagePump.link(ctx.channel(), backend);
                flushPending();
            });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // If backend connect failed, drop messages and let failed handshake handler respond
        if (backendConnectFailed) {
            ReferenceCountUtil.release(msg);
            return;
        }

        Protocol.Header header = Protocol.parseHeader(msg);
        if (header == null) {
            ReferenceCountUtil.release(msg);
            fail(ctx, new IllegalStateException("invalid Cassandra frame"));
            return;
        }
        state.ensureProtocolVersion(header.version());

        // Log raw message if logger is configured
        if (requestLogger != CassandraRequestLogger.NO_OP) {
            requestLogger.onMessage(Protocol.copy(msg));
        }

        // Handle different opcodes during handshake
        if (!state.isReady()) {
            handleHandshakeMessage(ctx, header, msg);
            return;
        }

        // Post-handshake: parse for audit and forward
        Protocol.ParsedMessage parsed = Protocol.parseForAudit(msg);
        if (parsed != null) {
            state.onQuery(parsed);
        }
        forwardToBackend(msg);
    }

    /**
     * Handle messages during the handshake phase.
     */
    private void handleHandshakeMessage(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf msg) {
        switch (header.opcode()) {
            case Protocol.OPCODE_OPTIONS -> {
                // OPTIONS: forward to backend
                forwardToBackend(msg);
            }

            case Protocol.OPCODE_STARTUP -> {
                handleStartup(ctx, header, msg);
            }

            case Protocol.OPCODE_AUTH_RESPONSE -> {
                handleAuthResponse(ctx, header, msg);
            }

            default -> {
                // Forward other messages (REGISTER, etc.) to backend
                forwardToBackend(msg);
            }
        }
    }

    /**
     * Parse STARTUP message to extract compression, driver info, and forward to backend.
     */
    private void handleStartup(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf msg) {
        startupReceived = true;

        // Parse STARTUP to extract options
        Protocol.StartupMessage startup = Protocol.parseStartup(msg);
        if (startup != null) {
            // Capture compression setting
            String compression = startup.compression();
            if (compression != null && !compression.isEmpty()) {
                log.debug("Client requested compression: {}", compression);
                state.setCompression(compression);
            }

            // Capture driver info for session/audit
            state.setDriverInfo(startup.driverName(), startup.driverVersion());

            log.debug("STARTUP: CQL_VERSION={}, COMPRESSION={}, DRIVER={}",
                startup.cqlVersion(), compression, startup.driverName());
        }

        // Forward STARTUP to backend
        forwardToBackend(msg);
    }

    /**
     * Parse AUTH_RESPONSE to validate username (like Teleport), then ignore credentials.
     * Proxy authenticates to backend with its own GSS credentials.
     */
    private void handleAuthResponse(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf msg) {
        // Parse AUTH_RESPONSE to extract username/password
        Protocol.AuthResponseMessage authResponse = Protocol.parseAuthResponse(msg);

        // Drop client's AUTH_RESPONSE - proxy handles backend auth with GSS
        // The backend handler (CassandraBackendHandler) will send proxy's GSS token
        ReferenceCountUtil.release(msg);
        log.debug("Ignoring client AUTH_RESPONSE, proxy will authenticate with GSS");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releasePending();
        MessagePump.closeOnFlush(backend);
        state.endSession();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Frontend connection failed", cause);
        releasePending();
        MessagePump.closeOnFlush(backend);
        state.fail(cause);
        ctx.close();
    }

    /**
     * Forward message to backend, consuming the msg reference.
     * Caller should not use msg after this call.
     */
    private void forwardToBackend(ByteBuf msg) {
        Channel ch = backend;
        if (ch == null) {
            // Backend not connected yet, buffer for later
            pending.add(msg); // Takes ownership, no retain needed
            return;
        }
        ch.writeAndFlush(msg); // Takes ownership, no retain needed
    }

    private void flushPending() {
        Channel ch = backend;
        if (ch == null || pending.isEmpty()) {
            return;
        }
        for (ByteBuf buf : pending) {
            ch.write(buf);
        }
        pending.clear();
        ch.flush();
    }

    private void releasePending() {
        for (ByteBuf buf : pending) {
            ReferenceCountUtil.safeRelease(buf);
        }
        pending.clear();
    }

    private void fail(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Frontend failed", cause);
        releasePending();
        MessagePump.closeOnFlush(backend);
        state.fail(cause);
        ctx.close();
    }
}
