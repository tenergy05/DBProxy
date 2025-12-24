package com.poc.pamport.cassandra;

import com.datastax.oss.protocol.internal.Compressor;
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
 * Matches Teleport's approach:
 * - Separate read/write framing modes (for version mismatch between client/server)
 * - Compression support (updates codec after STARTUP with compression)
 * - Switches to modern framing on READY/AUTHENTICATE, not on STARTUP
 *
 * Emits raw frame ByteBufs (retained) to downstream handlers.
 */
public final class CassandraFrameDecoder extends ByteToMessageDecoder {
    private static final int MAX_FRAME_LENGTH = 256 * 1024 * 1024;

    private final ByteBufPrimitiveCodec primitive = new ByteBufPrimitiveCodec();
    private FrameCodec<ByteBuf> frameCodec;
    private SegmentCodec<ByteBuf> segmentCodec;

    // Separate flags for read/write like Teleport - allows client v5 / server v4 scenarios
    private boolean modernFramingRead;
    private boolean modernFramingWrite;
    private ByteBuf fragmentedFrame;

    // Track negotiated version for responses
    private int negotiatedVersion = -1;

    public CassandraFrameDecoder() {
        Compressor<ByteBuf> noopCompressor = new NoopCompressor<>();
        this.frameCodec = FrameCodec.defaultServer(primitive, noopCompressor);
        this.segmentCodec = new SegmentCodec<>(primitive, noopCompressor);
    }

    /**
     * Update compression after seeing STARTUP with COMPRESSION option.
     * Called by handler after parsing STARTUP message.
     */
    public void updateCompression(String compression) {
        if (compression == null || compression.isEmpty() || "none".equalsIgnoreCase(compression)) {
            return;
        }
        Compressor<ByteBuf> compressor = createCompressor(compression);
        this.frameCodec = FrameCodec.defaultServer(primitive, compressor);
        this.segmentCodec = new SegmentCodec<>(primitive, compressor);
    }

    /**
     * Switch to modern framing layout for reading.
     * Called when READY or AUTHENTICATE is received (per Teleport pattern).
     */
    public void switchToModernFramingRead(int version) {
        if (!modernFramingRead && supportsModernFraming(version)) {
            modernFramingRead = true;
            negotiatedVersion = version;
        }
    }

    /**
     * Switch to modern framing layout for writing.
     * Called when READY or AUTHENTICATE is received (per Teleport pattern).
     */
    public void switchToModernFramingWrite(int version) {
        if (!modernFramingWrite && supportsModernFraming(version)) {
            modernFramingWrite = true;
            negotiatedVersion = version;
        }
    }

    /**
     * Check if version supports modern framing (v5+).
     */
    public static boolean supportsModernFraming(int version) {
        return version >= ProtocolConstants.Version.V5;
    }

    public boolean isModernFramingRead() {
        return modernFramingRead;
    }

    public boolean isModernFramingWrite() {
        return modernFramingWrite;
    }

    public int getNegotiatedVersion() {
        return negotiatedVersion;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            while (true) {
                if (!modernFramingRead) {
                    if (!decodeLegacyFrame(in, out)) {
                        return;
                    }
                } else {
                    if (!decodeModernSegment(in, out)) {
                        return;
                    }
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        }
    }

    /**
     * Decode legacy frame (v3/v4 style).
     * Returns true if a frame was decoded, false if waiting for more data.
     */
    private boolean decodeLegacyFrame(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < FrameCodec.V3_ENCODED_HEADER_SIZE) {
            return false;
        }
        int bodyLength = primitive.readInt(in, in.readerIndex() + FrameCodec.V3_ENCODED_HEADER_SIZE - 4);
        if (bodyLength < 0 || bodyLength > MAX_FRAME_LENGTH) {
            throw new DecoderException("Invalid Cassandra frame length: " + bodyLength);
        }
        int frameLength = FrameCodec.V3_ENCODED_HEADER_SIZE + bodyLength;
        if (in.readableBytes() < frameLength) {
            return false;
        }
        ByteBuf frameBuf = in.readRetainedSlice(frameLength);
        out.add(frameBuf);
        return true;
    }

    /**
     * Decode modern segment (v5+ style).
     * Returns true if a frame was decoded, false if waiting for more data.
     */
    private boolean decodeModernSegment(ByteBuf in, List<Object> out) {
        int headerLen = segmentCodec.headerLength() + SegmentCodec.CRC24_LENGTH;
        if (in.readableBytes() < headerLen) {
            return false;
        }

        // Decode header without consuming input
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
            return false;
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
        return true;
    }

    private void decodeSelfContained(ByteBuf payload, List<Object> out) {
        try {
            while (payload.readableBytes() >= FrameCodec.V3_ENCODED_HEADER_SIZE) {
                int bodyLen = primitive.readInt(payload, payload.readerIndex() + FrameCodec.V3_ENCODED_HEADER_SIZE - 4);
                int frameLen = FrameCodec.V3_ENCODED_HEADER_SIZE + bodyLen;
                if (bodyLen < 0 || bodyLen > MAX_FRAME_LENGTH || payload.readableBytes() < frameLen) {
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

    /**
     * Create compressor for the given compression algorithm.
     */
    private Compressor<ByteBuf> createCompressor(String compression) {
        return switch (compression.toLowerCase()) {
            case "lz4" -> new Lz4Compressor();
            case "snappy" -> new SnappyCompressor();
            default -> new NoopCompressor<>();
        };
    }

    /**
     * LZ4 compressor implementation using lz4-java.
     */
    private static final class Lz4Compressor implements Compressor<ByteBuf> {
        private final net.jpountz.lz4.LZ4Factory factory = net.jpountz.lz4.LZ4Factory.fastestInstance();

        @Override
        public ByteBuf compress(ByteBuf uncompressed) {
            byte[] src = new byte[uncompressed.readableBytes()];
            uncompressed.getBytes(uncompressed.readerIndex(), src);

            net.jpountz.lz4.LZ4Compressor compressor = factory.fastCompressor();
            int maxLen = compressor.maxCompressedLength(src.length);
            byte[] dest = new byte[4 + maxLen];
            int written = compressor.compress(src, 0, src.length, dest, 4, maxLen);
            java.nio.ByteBuffer.wrap(dest).putInt(0, src.length);
            return Unpooled.wrappedBuffer(dest, 0, 4 + written);
        }

        @Override
        public ByteBuf compressWithoutLength(ByteBuf uncompressed) {
            byte[] src = new byte[uncompressed.readableBytes()];
            uncompressed.getBytes(uncompressed.readerIndex(), src);

            net.jpountz.lz4.LZ4Compressor compressor = factory.fastCompressor();
            int maxLen = compressor.maxCompressedLength(src.length);
            byte[] dest = new byte[maxLen];
            int written = compressor.compress(src, 0, src.length, dest, 0, maxLen);
            return Unpooled.wrappedBuffer(dest, 0, written);
        }

        @Override
        public ByteBuf decompress(ByteBuf compressed) {
            try {
                int uncompressedLength = compressed.readInt();
                if (uncompressedLength == 0) {
                    return Unpooled.EMPTY_BUFFER;
                }
                byte[] compressedBytes = new byte[compressed.readableBytes()];
                compressed.readBytes(compressedBytes);

                net.jpountz.lz4.LZ4FastDecompressor decompressor = factory.fastDecompressor();
                byte[] restored = new byte[uncompressedLength];
                decompressor.decompress(compressedBytes, 0, restored, 0, uncompressedLength);
                return Unpooled.wrappedBuffer(restored);
            } catch (Exception e) {
                throw new IllegalStateException("LZ4 decompression failed", e);
            }
        }

        @Override
        public ByteBuf decompressWithoutLength(ByteBuf compressed, int uncompressedLength) {
            try {
                if (uncompressedLength == 0) {
                    return Unpooled.EMPTY_BUFFER;
                }
                byte[] compressedBytes = new byte[compressed.readableBytes()];
                compressed.readBytes(compressedBytes);

                net.jpountz.lz4.LZ4FastDecompressor decompressor = factory.fastDecompressor();
                byte[] restored = new byte[uncompressedLength];
                decompressor.decompress(compressedBytes, 0, restored, 0, uncompressedLength);
                return Unpooled.wrappedBuffer(restored);
            } catch (Exception e) {
                throw new IllegalStateException("LZ4 decompression failed", e);
            }
        }

        @Override
        public String algorithm() {
            return "lz4";
        }
    }

    /**
     * Snappy compressor implementation.
     */
    private static final class SnappyCompressor implements Compressor<ByteBuf> {
        @Override
        public ByteBuf compress(ByteBuf uncompressed) {
            try {
                byte[] src = new byte[uncompressed.readableBytes()];
                uncompressed.getBytes(uncompressed.readerIndex(), src);
                byte[] compressedBytes = org.xerial.snappy.Snappy.compress(src);
                return Unpooled.wrappedBuffer(compressedBytes);
            } catch (Exception e) {
                throw new IllegalStateException("Snappy compression failed", e);
            }
        }

        @Override
        public ByteBuf compressWithoutLength(ByteBuf uncompressed) {
            return compress(uncompressed);
        }

        @Override
        public ByteBuf decompress(ByteBuf compressed) {
            try {
                byte[] compressedBytes = new byte[compressed.readableBytes()];
                compressed.readBytes(compressedBytes);
                byte[] restored = org.xerial.snappy.Snappy.uncompress(compressedBytes);
                return Unpooled.wrappedBuffer(restored);
            } catch (Exception e) {
                throw new IllegalStateException("Snappy decompression failed", e);
            }
        }

        @Override
        public ByteBuf decompressWithoutLength(ByteBuf compressed, int uncompressedLength) {
            try {
                byte[] compressedBytes = new byte[compressed.readableBytes()];
                compressed.readBytes(compressedBytes);
                byte[] restored = org.xerial.snappy.Snappy.uncompress(compressedBytes);
                return Unpooled.wrappedBuffer(restored);
            } catch (Exception e) {
                throw new IllegalStateException("Snappy decompression failed", e);
            }
        }

        @Override
        public String algorithm() {
            return "snappy";
        }
    }
}
