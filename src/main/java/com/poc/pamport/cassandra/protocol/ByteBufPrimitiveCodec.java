package com.poc.pamport.cassandra.protocol;

import com.datastax.oss.protocol.internal.PrimitiveCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * PrimitiveCodec implementation over Netty ByteBuf, adapted from DataStax driver patterns.
 */
public final class ByteBufPrimitiveCodec implements PrimitiveCodec<ByteBuf> {
    @Override
    public ByteBuf allocate(int size) {
        return Unpooled.buffer(size);
    }

    @Override
    public void release(ByteBuf toRelease) {
        ReferenceCountUtil.release(toRelease);
    }

    @Override
    public int sizeOf(ByteBuf toMeasure) {
        return toMeasure.readableBytes();
    }

    @Override
    public ByteBuf concat(ByteBuf left, ByteBuf right) {
        return Unpooled.wrappedBuffer(left, right);
    }

    @Override
    public void markReaderIndex(ByteBuf source) {
        source.markReaderIndex();
    }

    @Override
    public void resetReaderIndex(ByteBuf source) {
        source.resetReaderIndex();
    }

    @Override
    public byte readByte(ByteBuf source) {
        return source.readByte();
    }

    @Override
    public int readInt(ByteBuf source) {
        return source.readInt();
    }

    @Override
    public int readInt(ByteBuf source, int offset) {
        return source.getInt(offset);
    }

    @Override
    public InetAddress readInetAddr(ByteBuf source) {
        int size = source.readByte() & 0xFF;
        byte[] bytes = new byte[size];
        source.readBytes(bytes);
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public long readLong(ByteBuf source) {
        return source.readLong();
    }

    @Override
    public int readUnsignedShort(ByteBuf source) {
        return source.readUnsignedShort();
    }

    @Override
    public ByteBuffer readBytes(ByteBuf source) {
        int length = source.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        source.readBytes(bytes);
        return ByteBuffer.wrap(bytes);
    }

    @Override
    public byte[] readShortBytes(ByteBuf source) {
        int length = source.readUnsignedShort();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        source.readBytes(bytes);
        return bytes;
    }

    @Override
    public String readString(ByteBuf source) {
        int length = source.readUnsignedShort();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        source.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String readLongString(ByteBuf source) {
        int length = source.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        source.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public ByteBuf readRetainedSlice(ByteBuf source, int sliceLength) {
        return source.readRetainedSlice(sliceLength);
    }

    @Override
    public void updateCrc(ByteBuf source, CRC32 crc) {
        int idx = source.readerIndex();
        int len = source.readableBytes();
        byte[] bytes = new byte[len];
        source.getBytes(idx, bytes);
        crc.update(bytes, 0, len);
    }

    @Override
    public void writeByte(byte b, ByteBuf dest) {
        dest.writeByte(b);
    }

    @Override
    public void writeInt(int i, ByteBuf dest) {
        dest.writeInt(i);
    }

    @Override
    public void writeInetAddr(InetAddress address, ByteBuf dest) {
        byte[] bytes = address.getAddress();
        dest.writeByte((byte) bytes.length);
        dest.writeBytes(bytes);
    }

    @Override
    public void writeLong(long l, ByteBuf dest) {
        dest.writeLong(l);
    }

    @Override
    public void writeUnsignedShort(int i, ByteBuf dest) {
        dest.writeShort(i);
    }

    @Override
    public void writeString(String s, ByteBuf dest) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dest.writeShort(bytes.length);
        dest.writeBytes(bytes);
    }

    @Override
    public void writeLongString(String s, ByteBuf dest) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dest.writeInt(bytes.length);
        dest.writeBytes(bytes);
    }

    @Override
    public void writeBytes(ByteBuffer bytes, ByteBuf dest) {
        if (bytes == null) {
            dest.writeInt(-1);
            return;
        }
        int length = bytes.remaining();
        dest.writeInt(length);
        dest.writeBytes(bytes.duplicate());
    }

    @Override
    public void writeBytes(byte[] bytes, ByteBuf dest) {
        dest.writeInt(bytes.length);
        dest.writeBytes(bytes);
    }

    @Override
    public void writeShortBytes(byte[] bytes, ByteBuf dest) {
        dest.writeShort(bytes.length);
        dest.writeBytes(bytes);
    }
}
