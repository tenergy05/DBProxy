package com.gravitational.teleport.dbproxy.cassandra;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Minimal Cassandra protocol frame decoder placeholder.
 *
 * Cassandra native protocol starts with a 4-byte length (after flags); this
 * decoder simply uses the first 4 bytes as length for framing.
 */
final class CassandraFrameDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < Integer.BYTES) {
            return;
        }
        int length = in.getInt(in.readerIndex());
        if (length <= 0 || in.readableBytes() < Integer.BYTES + length) {
            return;
        }
        // include the length field in the slice for simplicity.
        out.add(in.readRetainedSlice(Integer.BYTES + length));
    }
}
