package com.poc.pamport.postgres;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;

/**
 * PostgresFrameDecoder slices the incoming TCP stream into protocol frames.
 *
 * <p>It understands the startup packet (length + payload, no leading type byte)
 * and the regular messages (type byte followed by length that includes the
 * length field but excludes the leading type).</p>
 */
public final class PostgresFrameDecoder extends ByteToMessageDecoder {

    private final boolean expectStartupMessage;
    private boolean startupProcessed;

    public PostgresFrameDecoder(boolean expectStartupMessage) {
        this.expectStartupMessage = expectStartupMessage;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (expectStartupMessage && !startupProcessed) {
            if (in.readableBytes() < Integer.BYTES) {
                return;
            }
            int length = in.getInt(in.readerIndex());
            if (length < Integer.BYTES) {
                throw new CorruptedFrameException("invalid startup packet length: " + length);
            }
            if (in.readableBytes() < length) {
                return;
            }
            ByteBuf frame = in.readRetainedSlice(length);
            startupProcessed = true;
            out.add(frame);
            return;
        }

        if (in.readableBytes() < 1 + Integer.BYTES) {
            return;
        }
        int length = in.getInt(in.readerIndex() + 1);
        if (length < Integer.BYTES) {
            throw new CorruptedFrameException("invalid message length: " + length);
        }
        int totalLength = length + 1; // include the leading type byte.
        if (in.readableBytes() < totalLength) {
            return;
        }
        out.add(in.readRetainedSlice(totalLength));
    }
}
