package com.poc.pamport.cassandra;

import com.datastax.oss.protocol.internal.CrcMismatchException;
import com.datastax.oss.protocol.internal.FrameCodec;
import com.datastax.oss.protocol.internal.NoopCompressor;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.Segment;
import com.datastax.oss.protocol.internal.SegmentCodec;
import com.poc.pamport.cassandra.protocol.ByteBufPrimitiveCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.util.List;

/**
 * Frames Cassandra native protocol messages, including modern framing (segments) for v5+.
 *
 * Emits raw frame ByteBufs (retained) to downstream handlers.
 */
final class CassandraFrameDecoder extends ByteToMessageDecoder {
    private static final int MAX_FRAME_LENGTH = 256 * 1024 * 1024;

    private final ByteBufPrimitiveCodec primitive = new ByteBufPrimitiveCodec();
    private final FrameCodec<ByteBuf> frameCodec = FrameCodec.defaultServer(primitive, new NoopCompressor<>());
    private final SegmentCodec<ByteBuf> segmentCodec = new SegmentCodec<>(primitive, new NoopCompressor<>());

    private boolean modernFraming;
    private ByteBuf fragmentedFrame;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            while (true) {
                if (!modernFraming) {
                    if (in.readableBytes() < FrameCodec.V3_ENCODED_HEADER_SIZE) {
                        return;
                    }
                    int bodyLength = primitive.readInt(in, in.readerIndex() + FrameCodec.V3_ENCODED_HEADER_SIZE - 4);
                    if (bodyLength < 0 || bodyLength > MAX_FRAME_LENGTH) {
                        throw new DecoderException("Invalid Cassandra frame length: " + bodyLength);
                    }
                    int frameLength = FrameCodec.V3_ENCODED_HEADER_SIZE + bodyLength;
                    if (in.readableBytes() < frameLength) {
                        return;
                    }
                    ByteBuf frameBuf = in.readRetainedSlice(frameLength);
                    int version = frameBuf.getUnsignedByte(frameBuf.readerIndex()) & 0x7F;
                    if (version >= ProtocolConstants.Version.V5) {
                        modernFraming = true;
                    }
                    out.add(frameBuf);
                } else {
                    int headerLen = segmentCodec.headerLength() + SegmentCodec.CRC24_LENGTH;
                    if (in.readableBytes() < headerLen) {
                        return;
                    }
                    // Decode header without consuming in.
                    ByteBuf headerBuf = in.retainedSlice(in.readerIndex(), headerLen);
                    SegmentCodec.Header header;
                    try {
                        header = segmentCodec.decodeHeader(headerBuf);
                    } catch (CrcMismatchException e) {
                        throw new DecoderException(e);
                    } finally {
                        headerBuf.release();
                    }
                    int totalLen = headerLen + header.payloadLength + SegmentCodec.CRC32_LENGTH;
                    if (header.payloadLength < 0 || header.payloadLength > MAX_FRAME_LENGTH) {
                        throw new DecoderException("Invalid Cassandra segment payload length: " + header.payloadLength);
                    }
                    if (in.readableBytes() < totalLen) {
                        return;
                    }
                    ByteBuf segBuf = in.readRetainedSlice(totalLen);
                    ByteBuf payloadPlusCrc = segBuf.retainedSlice(headerLen, header.payloadLength + SegmentCodec.CRC32_LENGTH);
                    Segment<ByteBuf> segment;
                    try {
                        segment = segmentCodec.decode(header, payloadPlusCrc);
                    } catch (CrcMismatchException e) {
                        payloadPlusCrc.release();
                        segBuf.release();
                        throw new DecoderException(e);
                    }
                    segBuf.release();
                    ByteBuf payload = segment.payload;
                    if (segment.isSelfContained) {
                        decodeSelfContained(payload, out);
                    } else {
                        accumulateFragment(payload, out);
                    }
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        }
    }

    private void decodeSelfContained(ByteBuf payload, List<Object> out) {
        try {
            while (payload.readableBytes() >= FrameCodec.V3_ENCODED_HEADER_SIZE) {
                int bodyLen = primitive.readInt(payload, payload.readerIndex() + FrameCodec.V3_ENCODED_HEADER_SIZE - 4);
                int frameLen = FrameCodec.V3_ENCODED_HEADER_SIZE + bodyLen;
                if (bodyLen < 0 || bodyLen > MAX_FRAME_LENGTH || payload.readableBytes() < frameLen) {
                    // invalid; drop and throw
                    throw new DecoderException("Invalid Cassandra frame length inside segment: " + bodyLen);
                }
                out.add(payload.readRetainedSlice(frameLen));
            }
        } finally {
            payload.release();
        }
    }

    private void accumulateFragment(ByteBuf payload, List<Object> out) {
        if (fragmentedFrame == null) {
            fragmentedFrame = payload;
        } else {
            ByteBuf combined = Unpooled.wrappedBuffer(fragmentedFrame, payload);
            fragmentedFrame = combined;
        }
        decodeAccumulated(out);
    }

    private void decodeAccumulated(List<Object> out) {
        if (fragmentedFrame == null) {
            return;
        }
        while (fragmentedFrame.readableBytes() >= FrameCodec.V3_ENCODED_HEADER_SIZE) {
            int bodyLen = primitive.readInt(fragmentedFrame, fragmentedFrame.readerIndex() + FrameCodec.V3_ENCODED_HEADER_SIZE - 4);
            int frameLen = FrameCodec.V3_ENCODED_HEADER_SIZE + bodyLen;
            if (bodyLen < 0 || bodyLen > MAX_FRAME_LENGTH) {
                releaseFragmented();
                throw new DecoderException("Invalid Cassandra frame length inside fragmented segment: " + bodyLen);
            }
            if (fragmentedFrame.readableBytes() < frameLen) {
                return; // wait for more
            }
            out.add(fragmentedFrame.readRetainedSlice(frameLen));
        }
        if (!fragmentedFrame.isReadable()) {
            releaseFragmented();
        }
    }

    private void releaseFragmented() {
        if (fragmentedFrame != null) {
            fragmentedFrame.release();
            fragmentedFrame = null;
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        releaseFragmented();
        super.handlerRemoved0(ctx);
    }
}
