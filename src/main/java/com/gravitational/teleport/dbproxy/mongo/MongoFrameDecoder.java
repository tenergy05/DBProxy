package com.gravitational.teleport.dbproxy.mongo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Minimal MongoDB frame decoder placeholder.
 *
 * Mongo wire protocol is length-prefixed (int32 messageLength at start).
 * This decoder slices frames based on that first int32.
 */
final class MongoFrameDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < Integer.BYTES) {
            return;
        }
        int length = in.getInt(in.readerIndex());
        if (length <= 0 || in.readableBytes() < length) {
            return;
        }
        out.add(in.readRetainedSlice(length));
    }
}
