package com.poc.pamport.dbproxy.cassandra;

import com.poc.pamport.dbproxy.core.MessagePump;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles backend Cassandra frames during handshake, then forwards responses.
 */
final class CassandraBackendHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(CassandraBackendHandler.class);

    private final CassandraHandshakeState state;

    CassandraBackendHandler(CassandraHandshakeState state) {
        this.state = state;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        CassandraMessages.FrameHeader header = CassandraMessages.parseHeader(msg);
        if (header == null) {
            log.warn("Dropping invalid Cassandra frame from backend");
            return;
        }

        switch (header.opcode()) {
            case CassandraMessages.OPCODE_SUPPORTED -> state.forwardToFrontend(msg);
            case CassandraMessages.OPCODE_AUTHENTICATE -> handleAuthenticate(ctx, header, msg);
            case CassandraMessages.OPCODE_AUTH_CHALLENGE -> handleAuthChallenge(ctx, header, msg);
            case CassandraMessages.OPCODE_AUTH_SUCCESS -> log.debug("Backend auth success");
            case CassandraMessages.OPCODE_READY -> handleReady(ctx, header, msg);
            case CassandraMessages.OPCODE_ERROR -> {
                state.forwardToFrontend(msg);
                ctx.close();
            }
            default -> {
                if (state.isReady()) {
                    state.forwardToFrontend(msg);
                } else {
                    // Forward unknown pre-ready messages conservatively.
                    state.forwardToFrontend(msg);
                }
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        MessagePump.closeOnFlush(state.frontend());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Backend connection failed", cause);
        MessagePump.closeOnFlush(state.frontend());
        state.fail(cause);
        ctx.close();
    }

    private void handleAuthenticate(ChannelHandlerContext ctx, CassandraMessages.FrameHeader header, ByteBuf frame) {
        // Forward AUTHENTICATE to client to keep its state machine aligned.
        state.forwardToFrontend(frame);
        byte[] token = state.gss().initialToken();
        state.sendAuthResponse(ctx, header, token);
    }

    private void handleAuthChallenge(ChannelHandlerContext ctx, CassandraMessages.FrameHeader header, ByteBuf frame) {
        // Forward AUTH_CHALLENGE to client for compatibility, even though the proxy answers it.
        state.forwardToFrontend(frame);
        byte[] challenge = CassandraMessages.readAuthToken(frame, header.bodyLength());
        byte[] token = state.gss().challenge(challenge);
        state.sendAuthResponse(ctx, header, token);
    }

    private void handleReady(ChannelHandlerContext ctx, CassandraMessages.FrameHeader header, ByteBuf frame) {
        state.forwardToFrontend(frame);
        state.markReady();
        state.flushPending();
    }

}
