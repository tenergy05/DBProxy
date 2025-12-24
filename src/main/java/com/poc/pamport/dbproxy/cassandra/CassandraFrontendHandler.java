package com.poc.pamport.dbproxy.cassandra;

import com.poc.pamport.dbproxy.core.BackendConnector;
import com.poc.pamport.dbproxy.core.MessagePump;
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

    private final CassandraProxyServer.Config config;
    private final CassandraRequestLogger requestLogger;
    private final List<ByteBuf> pending = new ArrayList<>();
    private final CassandraHandshakeState state;
    private Channel backend;

    CassandraFrontendHandler(CassandraProxyServer.Config config) {
        this.config = config;
        this.requestLogger = config.requestLogger;
        this.state = new CassandraHandshakeState(config);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
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
        CassandraMessages.FrameHeader header = CassandraMessages.parseHeader(msg);
        if (header == null) {
            ReferenceCountUtil.release(msg);
            fail(ctx, new IllegalStateException("invalid Cassandra frame"));
            return;
        }
        state.ensureProtocolVersion(header.version());

        if (requestLogger != CassandraRequestLogger.NO_OP) {
            requestLogger.onMessage(CassandraMessages.copy(msg));
        }

        if (!state.isReady()) {
            if (header.opcode() == CassandraMessages.OPCODE_OPTIONS
                || header.opcode() == CassandraMessages.OPCODE_STARTUP) {
                forwardToBackend(msg);
            } else {
                pending.add(msg.retain());
            }
            return;
        }

        forwardToBackend(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releasePending();
        MessagePump.closeOnFlush(backend);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Frontend connection failed", cause);
        releasePending();
        MessagePump.closeOnFlush(backend);
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
        ctx.close();
    }
}
