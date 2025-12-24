package com.poc.pamport.dbproxy.cassandra;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.util.List;

/**
 * Frames Cassandra native protocol messages.
 *
 * The native protocol header is always 9 bytes (version, flags, stream id, opcode, body length).
 * The body length field starts at offset 5 and covers only the payload, so total frame length is
 * 9 + bodyLength. This layout is stable across v4/v5/v6 (and DSE 6.6) and works for SASL
 * authentication without needing to understand message contents.
 */
final class CassandraFrameDecoder extends ByteToMessageDecoder {
    private static final int HEADER_LENGTH = 9;
    private static final int LENGTH_FIELD_OFFSET = 5;
    // Align with Cassandra's native_transport_max_frame_size_in_mb default (256MB).
    private static final int MAX_FRAME_LENGTH = 256 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < HEADER_LENGTH) {
            return; // wait for full header
        }

        int bodyLength = in.getInt(in.readerIndex() + LENGTH_FIELD_OFFSET);
        if (bodyLength < 0 || bodyLength > MAX_FRAME_LENGTH) {
            throw new DecoderException("Invalid Cassandra frame length: " + bodyLength);
        }

        int frameLength = HEADER_LENGTH + bodyLength;
        if (in.readableBytes() < frameLength) {
            return; // wait for the full frame
        }

        out.add(in.readRetainedSlice(frameLength));
    }
}
