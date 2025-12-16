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
 * CassandraFrontendHandler forwards Cassandra native protocol messages and invokes optional logging hook.
 */
final class CassandraFrontendHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(CassandraFrontendHandler.class);

    private final BackendConnector connector;
    private final CassandraRequestLogger requestLogger;
    private final List<ByteBuf> pending = new ArrayList<>();
    private Channel backend;

    CassandraFrontendHandler(BackendConnector connector, CassandraRequestLogger requestLogger) {
        this.connector = connector;
        this.requestLogger = requestLogger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connector.connect(ctx.channel())
            .addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.warn("Failed to connect to backend", future.cause());
                    ctx.close();
                    return;
                }
                backend = future.channel();
                MessagePump.link(ctx.channel(), backend);
                flushPending();
            });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        byte[] copy = new byte[msg.readableBytes()];
        msg.getBytes(msg.readerIndex(), copy);
        requestLogger.onMessage(copy);

        if (backend == null) {
            pending.add(msg.retain());
            return;
        }
        backend.writeAndFlush(msg.retain());
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

    private void flushPending() {
        if (backend == null || pending.isEmpty()) {
            return;
        }
        for (ByteBuf buf : pending) {
            backend.write(buf);
        }
        pending.clear();
        backend.flush();
    }

    private void releasePending() {
        for (ByteBuf buf : pending) {
            ReferenceCountUtil.safeRelease(buf);
        }
        pending.clear();
    }
}
