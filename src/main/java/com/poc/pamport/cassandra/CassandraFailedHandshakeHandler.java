package com.poc.pamport.cassandra;

import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.poc.pamport.cassandra.protocol.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits a server-side handshake error to the client without contacting the backend,
 * mirroring Teleport's failedHandshake path: OPTIONS->SUPPORTED, STARTUP->AUTHENTICATE,
 * AUTH_RESPONSE->ERROR then close.
 */
final class CassandraFailedHandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(CassandraFailedHandshakeHandler.class);

    private final String errorMessage;
    private boolean complete;

    CassandraFailedHandshakeHandler(String errorMessage) {
        this.errorMessage = errorMessage == null ? "authentication failed" : errorMessage;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (complete) {
            ReferenceCountUtil.release(msg);
            return;
        }
        Protocol.Header header = Protocol.parseHeader(msg);
        if (header == null) {
            ReferenceCountUtil.release(msg);
            return;
        }
        int version = header.version();
        int streamId = header.streamId();
        switch (header.opcode()) {
            case Protocol.OPCODE_OPTIONS -> ctx.writeAndFlush(buildSupported(ctx, version, streamId));
            case Protocol.OPCODE_STARTUP -> ctx.writeAndFlush(buildAuthenticate(ctx, version, streamId));
            case Protocol.OPCODE_AUTH_RESPONSE -> {
                ctx.writeAndFlush(buildError(ctx, version, streamId, errorMessage))
                    .addListener(f -> ctx.close());
                complete = true;
            }
            default -> ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Failed handshake error handler caught exception", cause);
        ctx.close();
    }

    private static ByteBuf buildSupported(ChannelHandlerContext ctx, int version, int streamId) {
        // Supported: map<string, string list> with CQL_VERSION=3.4.5, COMPRESSION={}
        byte[] cqlKey = "CQL_VERSION".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] cqlVal = "3.4.5".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compKey = "COMPRESSION".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int mapEntries = 2;
        int bodyLen =
            Short.BYTES // map length
                + Short.BYTES + cqlKey.length + Short.BYTES // key len+bytes + list len
                + Short.BYTES + cqlVal.length // one value
                + Short.BYTES + compKey.length + Short.BYTES; // empty list
        ByteBuf buf = ctx.alloc().buffer(Protocol.HEADER_LENGTH + bodyLen);
        buf.writeByte((byte) (0x80 | version));
        buf.writeByte(0); // flags
        buf.writeShort(streamId);
        buf.writeByte(Protocol.OPCODE_SUPPORTED);
        buf.writeInt(bodyLen);
        buf.writeShort(mapEntries);
        // CQL_VERSION entry
        buf.writeShort(cqlKey.length);
        buf.writeBytes(cqlKey);
        buf.writeShort(1); // list size
        buf.writeShort(cqlVal.length);
        buf.writeBytes(cqlVal);
        // COMPRESSION entry (empty)
        buf.writeShort(compKey.length);
        buf.writeBytes(compKey);
        buf.writeShort(0); // empty list
        return buf;
    }

    private static ByteBuf buildAuthenticate(ChannelHandlerContext ctx, int version, int streamId) {
        byte[] authenticator = "org.apache.cassandra.auth.PasswordAuthenticator".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int bodyLen = Short.BYTES + authenticator.length;
        ByteBuf buf = ctx.alloc().buffer(Protocol.HEADER_LENGTH + bodyLen);
        buf.writeByte((byte) (0x80 | version));
        buf.writeByte(0);
        buf.writeShort(streamId);
        buf.writeByte(Protocol.OPCODE_AUTHENTICATE);
        buf.writeInt(bodyLen);
        buf.writeShort(authenticator.length);
        buf.writeBytes(authenticator);
        return buf;
    }

    private static ByteBuf buildError(ChannelHandlerContext ctx, int version, int streamId, String message) {
        byte[] msgBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int bodyLen = Integer.BYTES + Short.BYTES + msgBytes.length; // code + string
        ByteBuf buf = ctx.alloc().buffer(Protocol.HEADER_LENGTH + bodyLen);
        buf.writeByte((byte) (0x80 | version));
        buf.writeByte(0);
        buf.writeShort(streamId);
        buf.writeByte(Protocol.OPCODE_ERROR);
        buf.writeInt(bodyLen);
        buf.writeInt(ProtocolConstants.ErrorCode.AUTH_ERROR);
        buf.writeShort(msgBytes.length);
        buf.writeBytes(msgBytes);
        return buf;
    }
}
