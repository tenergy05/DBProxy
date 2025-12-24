package com.poc.pamport.cassandra;

import com.poc.pamport.core.MessagePump;
import com.poc.pamport.cassandra.protocol.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles backend Cassandra frames during handshake, then forwards responses.
 *
 * Enhanced to match Teleport's approach:
 * - Switches to modern framing on READY/AUTHENTICATE (not on STARTUP)
 * - Properly handles version negotiation between client and server
 * - Manages GSS authentication handshake with backend
 */
final class CassandraBackendHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(CassandraBackendHandler.class);

    private final CassandraHandshakeState state;

    CassandraBackendHandler(CassandraHandshakeState state) {
        this.state = state;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        Protocol.Header header = Protocol.parseHeader(msg);
        if (header == null) {
            log.warn("Dropping invalid Cassandra frame from backend");
            return;
        }

        switch (header.opcode()) {
            case Protocol.OPCODE_SUPPORTED -> {
                // Forward SUPPORTED to client
                state.forwardToFrontend(msg);
            }

            case Protocol.OPCODE_AUTHENTICATE -> {
                handleAuthenticate(ctx, header, msg);
            }

            case Protocol.OPCODE_AUTH_CHALLENGE -> {
                handleAuthChallenge(ctx, header, msg);
            }

            case Protocol.OPCODE_AUTH_SUCCESS -> {
                handleAuthSuccess(ctx, header, msg);
            }

            case Protocol.OPCODE_READY -> {
                handleReady(ctx, header, msg);
            }

            case Protocol.OPCODE_ERROR -> {
                log.warn("Backend sent ERROR during handshake");
                state.forwardToFrontend(msg);
                ctx.close();
            }

            default -> {
                if (state.isReady()) {
                    state.forwardToFrontend(msg);
                } else {
                    // Forward unknown pre-ready messages conservatively
                    log.debug("Forwarding unknown opcode {} during handshake", header.opcode());
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

    /**
     * Handle AUTHENTICATE from backend.
     * This triggers the GSS handshake - we send our initial GSS token.
     * Like Teleport, we switch to modern framing here if version supports it.
     */
    private void handleAuthenticate(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf frame) {
        // Parse authenticator class for logging
        String authenticatorClass = Protocol.parseAuthenticateClass(frame);
        log.debug("Backend AUTHENTICATE: {}", authenticatorClass);

        // Switch to modern framing on AUTHENTICATE (like Teleport)
        // This is when we know the negotiated version from the server
        maybeSwitchToModernFraming(header.version());

        // Forward AUTHENTICATE to client to keep its state machine aligned
        state.forwardToFrontend(frame);

        // Start GSS authentication with backend
        try {
            byte[] token = state.gss().initialToken();
            state.sendAuthResponse(ctx, header, token);
            log.debug("Sent initial GSS token to backend ({} bytes)", token.length);
        } catch (Exception e) {
            log.error("GSS initial token generation failed", e);
            state.fail(e);
            ctx.close();
        }
    }

    /**
     * Handle AUTH_CHALLENGE from backend.
     * Continue GSS handshake with challenge-response.
     */
    private void handleAuthChallenge(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf frame) {
        // Forward AUTH_CHALLENGE to client for compatibility (even though proxy answers it)
        state.forwardToFrontend(frame);

        // Extract challenge token from frame
        byte[] challenge = Protocol.readAuthToken(frame, header.bodyLength());
        log.debug("Received GSS challenge from backend ({} bytes)", challenge.length);

        try {
            byte[] token = state.gss().challenge(challenge);
            state.sendAuthResponse(ctx, header, token);
            log.debug("Sent GSS response to backend ({} bytes)", token.length);
        } catch (Exception e) {
            log.error("GSS challenge-response failed", e);
            state.fail(e);
            ctx.close();
        }
    }

    /**
     * Handle AUTH_SUCCESS from backend.
     * GSS handshake complete, forward to client.
     */
    private void handleAuthSuccess(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf frame) {
        log.debug("Backend AUTH_SUCCESS - GSS handshake complete");

        // Switch to modern framing if not already done
        maybeSwitchToModernFraming(header.version());

        // Forward AUTH_SUCCESS to client
        state.forwardToFrontend(frame);

        // Mark session as ready
        state.markReady();
        state.flushPending();
    }

    /**
     * Handle READY from backend.
     * Server is ready (no authentication required or auth complete).
     */
    private void handleReady(ChannelHandlerContext ctx, Protocol.Header header, ByteBuf frame) {
        log.debug("Backend READY");

        // Switch to modern framing on READY (like Teleport)
        maybeSwitchToModernFraming(header.version());

        // Forward READY to client
        state.forwardToFrontend(frame);

        // Mark session as ready
        state.markReady();
        state.flushPending();
    }

    /**
     * Switch both frontend and backend decoders to modern framing if version supports it.
     * This is called on READY or AUTHENTICATE (like Teleport's maybeSwitchToModernLayout).
     */
    private void maybeSwitchToModernFraming(int version) {
        if (CassandraFrameDecoder.supportsModernFraming(version)) {
            log.debug("Switching to modern framing for protocol version {}", version);
            state.switchFrontendToModernFraming(version);
            state.switchBackendToModernFraming(version);
        }
    }
}
