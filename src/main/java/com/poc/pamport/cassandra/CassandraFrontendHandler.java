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

    CassandraFrontendHandler(CassandraEngine.Config config) {
        this.config = config;
        this.requestLogger = config.requestLogger;
        this.state = new CassandraHandshakeState(config);
        this.auditRecorder = config.auditRecorder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.session = auditRecorder.newSession(ctx.channel().remoteAddress());
        this.session.setProtocol("cassandra");
        this.session.setDatabaseType("cassandra");
        this.session.setDatabaseProtocol("cassandra");
        this.session.setDatabaseService(config.targetHost + ":" + config.targetPort);
        state.session(session);
        state.frontend(ctx.channel());
        BackendConnector connector = new BackendConnector(
            config.targetHost,
            config.targetPort,
            frontend -> new CassandraBackendPipelineInitializer(state)
        );
        connector.connect(ctx.channel())
            .addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.warn("Failed to connect to backend", future.cause());
                    ctx.close();
                    return;
                }
                backend = future.channel();
                state.backend(backend);
                MessagePump.link(ctx.channel(), backend);
                flushPending();
            });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        Protocol.Header header = Protocol.parseHeader(msg);
        if (header == null) {
            ReferenceCountUtil.release(msg);
            fail(ctx, new IllegalStateException("invalid Cassandra frame"));
            return;
        }
        state.ensureProtocolVersion(header.version());

        if (requestLogger != CassandraRequestLogger.NO_OP) {
            requestLogger.onMessage(Protocol.copy(msg));
        }

        if (!state.isReady()) {
            if (header.opcode() == Protocol.OPCODE_AUTH_RESPONSE) {
                // Client-provided auth is ignored; proxy authenticates on behalf of the client.
                ReferenceCountUtil.release(msg);
                return;
            }
            forwardToBackend(msg);
            return;
        }

        Protocol.ParsedMessage parsed = Protocol.parseForAudit(msg);
        if (parsed != null) {
            state.onQuery(parsed);
        }
        forwardToBackend(msg);
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

    private void forwardToBackend(ByteBuf msg) {
        Channel ch = backend;
        if (ch == null) {
            pending.add(msg.retain());
            return;
        }
        ch.writeAndFlush(msg.retain());
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
