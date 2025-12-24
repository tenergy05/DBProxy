package com.poc.pamport.dbproxy.cassandra;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class CassandraMessages {
    static final int HEADER_LENGTH = 9;

    static final int OPCODE_ERROR = 0x00;
    static final int OPCODE_STARTUP = 0x01;
    static final int OPCODE_READY = 0x02;
    static final int OPCODE_AUTHENTICATE = 0x03;
    static final int OPCODE_SUPPORTED = 0x06;
    static final int OPCODE_QUERY = 0x07;
    static final int OPCODE_RESULT = 0x08;
    static final int OPCODE_PREPARE = 0x09;
    static final int OPCODE_EXECUTE = 0x0A;
    static final int OPCODE_REGISTER = 0x0B;
    static final int OPCODE_EVENT = 0x0C;
    static final int OPCODE_BATCH = 0x0D;
    static final int OPCODE_AUTH_CHALLENGE = 0x0E;
    static final int OPCODE_AUTH_RESPONSE = 0x0F;
    static final int OPCODE_AUTH_SUCCESS = 0x10;
    static final int OPCODE_OPTIONS = 0x05;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private CassandraMessages() {}

    static FrameHeader parseHeader(ByteBuf frame) {
        if (frame.readableBytes() < HEADER_LENGTH) {
            return null;
        }
        int versionByte = frame.getUnsignedByte(frame.readerIndex());
        boolean response = (versionByte & 0x80) != 0;
        int version = versionByte & 0x7F;
        int flags = frame.getUnsignedByte(frame.readerIndex() + 1);
        int streamId = frame.getUnsignedShort(frame.readerIndex() + 2);
        int opcode = frame.getUnsignedByte(frame.readerIndex() + 4);
        int bodyLength = frame.getInt(frame.readerIndex() + 5);
        return new FrameHeader(version, response, flags, streamId, opcode, bodyLength);
    }

    static byte[] copy(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    static byte[] readAuthToken(ByteBuf frame, int bodyLength) {
        int bodyStart = frame.readerIndex() + HEADER_LENGTH;
        if (frame.readableBytes() < HEADER_LENGTH + Integer.BYTES) {
            return new byte[0];
        }
        int len = frame.getInt(bodyStart);
        if (len < 0 || len > bodyLength - Integer.BYTES || bodyStart + Integer.BYTES + len > frame.readerIndex() + HEADER_LENGTH + bodyLength) {
            return new byte[0];
        }
        byte[] out = new byte[len];
        frame.getBytes(bodyStart + Integer.BYTES, out);
        return out;
    }

    static ParsedMessage parseForAudit(ByteBuf frame) {
        FrameHeader header = parseHeader(frame);
        if (header == null) {
            return null;
        }
        ByteBuf body = frame.slice(frame.readerIndex() + HEADER_LENGTH, header.bodyLength());
        return switch (header.opcode()) {
            case OPCODE_QUERY -> new ParsedMessage("QUERY", readLongString(body));
            case OPCODE_PREPARE -> new ParsedMessage("PREPARE", readLongString(body));
            case OPCODE_EXECUTE -> new ParsedMessage("EXECUTE", "id=" + readBytesHex(body));
            case OPCODE_BATCH -> new ParsedMessage("BATCH", parseBatch(body));
            case OPCODE_REGISTER -> new ParsedMessage("REGISTER", String.join(",", readStringList(body)));
            default -> null;
        };
    }

    private static String parseBatch(ByteBuf body) {
        if (!body.isReadable()) {
            return "";
        }
        int type = body.readUnsignedByte();
        int n = body.readUnsignedShort();
        List<String> stmts = new ArrayList<>();
        for (int i = 0; i < n && body.isReadable(); i++) {
            int kind = body.readUnsignedByte();
            if (kind == 0) { // query string
                stmts.add(readLongString(body));
            } else {
                stmts.add("id=" + readShortBytesHex(body));
            }
        }
        return "type=" + type + " statements=" + String.join(" | ", stmts);
    }

    private static String readLongString(ByteBuf body) {
        if (body.readableBytes() < Integer.BYTES) {
            return "";
        }
        int len = body.readInt();
        if (len < 0 || body.readableBytes() < len) {
            return "";
        }
        byte[] data = new byte[len];
        body.readBytes(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static List<String> readStringList(ByteBuf body) {
        List<String> out = new ArrayList<>();
        if (body.readableBytes() < Short.BYTES) {
            return out;
        }
        int count = body.readUnsignedShort();
        for (int i = 0; i < count && body.readableBytes() >= Short.BYTES; i++) {
            out.add(readString(body));
        }
        return out;
    }

    private static String readString(ByteBuf body) {
        if (body.readableBytes() < Short.BYTES) {
            return "";
        }
        int len = body.readUnsignedShort();
        if (len < 0 || body.readableBytes() < len) {
            return "";
        }
        byte[] data = new byte[len];
        body.readBytes(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readBytesHex(ByteBuf body) {
        if (body.readableBytes() < Integer.BYTES) {
            return "";
        }
        int len = body.readInt();
        if (len < 0 || body.readableBytes() < len) {
            return "";
        }
        byte[] data = new byte[len];
        body.readBytes(data);
        return HEX.formatHex(data);
    }

    private static String readShortBytesHex(ByteBuf body) {
        if (body.readableBytes() < Short.BYTES) {
            return "";
        }
        int len = body.readUnsignedShort();
        if (len < 0 || body.readableBytes() < len) {
            return "";
        }
        byte[] data = new byte[len];
        body.readBytes(data);
        return HEX.formatHex(data);
    }

    record FrameHeader(int version, boolean response, int flags, int streamId, int opcode, int bodyLength) {}

    record ParsedMessage(String kind, String detail) {}
}
