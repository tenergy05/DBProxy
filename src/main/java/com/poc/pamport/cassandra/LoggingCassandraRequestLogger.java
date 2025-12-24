package com.poc.pamport.cassandra;

import com.poc.pamport.cassandra.protocol.Protocol;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoggingCassandraRequestLogger dumps Cassandra frames (hex) for audit/debug.
 */
public final class LoggingCassandraRequestLogger implements CassandraRequestLogger {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final Logger log;
    private final boolean hexDump;

    public LoggingCassandraRequestLogger() {
        this(LoggerFactory.getLogger(LoggingCassandraRequestLogger.class), true);
    }

    public LoggingCassandraRequestLogger(Logger log, boolean hexDump) {
        this.log = log;
        this.hexDump = hexDump;
    }

    @Override
    public void onMessage(byte[] bytes) {
        Protocol.Header header = parseHeader(bytes);
        String prefix;
        if (header != null) {
            String detail = detailForAudit(bytes, header);
            prefix = String.format(
                "Cassandra v%d %s stream=%d opcode=%s flags=0x%02X len=%d bytes %s",
                header.version(),
                header.response() ? "resp" : "req ",
                header.streamId(),
                opcodeName(header.opcode()),
                header.flags(),
                header.bodyLength(),
                detail);
        } else {
            prefix = "Cassandra frame";
        }

        if (hexDump) {
            log.info("{} ({} total): {}", prefix, bytes.length, HEX.formatHex(bytes));
        } else {
            log.info("{} ({} total)", prefix, bytes.length);
        }
    }

    private static Protocol.Header parseHeader(byte[] bytes) {
        if (bytes.length < Protocol.HEADER_LENGTH) {
            return null;
        }
        int versionByte = bytes[0] & 0xFF;
        boolean response = (versionByte & 0x80) != 0;
        int version = versionByte & 0x7F;
        int flags = bytes[1] & 0xFF;
        int streamId = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        int opcode = bytes[4] & 0xFF;
        int bodyLength =
            ((bytes[5] & 0xFF) << 24)
                | ((bytes[6] & 0xFF) << 16)
                | ((bytes[7] & 0xFF) << 8)
                | (bytes[8] & 0xFF);
        return new Protocol.Header(version, response, flags, streamId, opcode, bodyLength);
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Protocol.OPCODE_ERROR -> "ERROR";
            case Protocol.OPCODE_STARTUP -> "STARTUP";
            case Protocol.OPCODE_READY -> "READY";
            case Protocol.OPCODE_AUTHENTICATE -> "AUTHENTICATE";
            case Protocol.OPCODE_OPTIONS -> "OPTIONS";
            case Protocol.OPCODE_SUPPORTED -> "SUPPORTED";
            case Protocol.OPCODE_QUERY -> "QUERY";
            case Protocol.OPCODE_RESULT -> "RESULT";
            case Protocol.OPCODE_PREPARE -> "PREPARE";
            case Protocol.OPCODE_EXECUTE -> "EXECUTE";
            case Protocol.OPCODE_REGISTER -> "REGISTER";
            case Protocol.OPCODE_EVENT -> "EVENT";
            case Protocol.OPCODE_BATCH -> "BATCH";
            case Protocol.OPCODE_AUTH_CHALLENGE -> "AUTH_CHALLENGE";
            case Protocol.OPCODE_AUTH_RESPONSE -> "AUTH_RESPONSE";
            case Protocol.OPCODE_AUTH_SUCCESS -> "AUTH_SUCCESS";
            default -> String.format("0x%02X", opcode);
        };
    }

    private static String detailForAudit(byte[] frame, Protocol.Header header) {
        if (header.opcode() != Protocol.OPCODE_QUERY && header.opcode() != Protocol.OPCODE_PREPARE
            && header.opcode() != Protocol.OPCODE_EXECUTE && header.opcode() != Protocol.OPCODE_REGISTER
            && header.opcode() != Protocol.OPCODE_BATCH) {
            return "";
        }
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame, Protocol.HEADER_LENGTH, header.bodyLength());
            buf.order(java.nio.ByteOrder.BIG_ENDIAN);
            return switch (header.opcode()) {
                case Protocol.OPCODE_QUERY -> "query=" + readLongString(buf);
                case Protocol.OPCODE_PREPARE -> "prepare=" + readLongString(buf);
                case Protocol.OPCODE_EXECUTE -> "execute id=" + readBytesHex(buf);
                case Protocol.OPCODE_REGISTER -> "register=" + String.join(",", readStringList(buf));
                case Protocol.OPCODE_BATCH -> "batch=" + parseBatch(buf);
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private static String parseBatch(java.nio.ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return "";
        }
        int type = Byte.toUnsignedInt(buf.get());
        int n = Short.toUnsignedInt(buf.getShort());
        java.util.List<String> stmts = new java.util.ArrayList<>();
        for (int i = 0; i < n && buf.remaining() > 0; i++) {
            int kind = Byte.toUnsignedInt(buf.get());
            if (kind == 0) {
                stmts.add(readLongString(buf));
            } else {
                stmts.add("id=" + readShortBytesHex(buf));
            }
        }
        return "type=" + type + " statements=" + String.join(" | ", stmts);
    }

    private static String readLongString(java.nio.ByteBuffer buf) {
        if (buf.remaining() < Integer.BYTES) {
            return "";
        }
        int len = buf.getInt();
        if (len < 0 || buf.remaining() < len) {
            return "";
        }
        byte[] data = new byte[len];
        buf.get(data);
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readBytesHex(java.nio.ByteBuffer buf) {
        if (buf.remaining() < Integer.BYTES) {
            return "";
        }
        int len = buf.getInt();
        if (len < 0 || buf.remaining() < len) {
            return "";
        }
        byte[] data = new byte[len];
        buf.get(data);
        return HEX.formatHex(data);
    }

    private static String readShortBytesHex(java.nio.ByteBuffer buf) {
        if (buf.remaining() < Short.BYTES) {
            return "";
        }
        int len = Short.toUnsignedInt(buf.getShort());
        if (len < 0 || buf.remaining() < len) {
            return "";
        }
        byte[] data = new byte[len];
        buf.get(data);
        return HEX.formatHex(data);
    }

    private static java.util.List<String> readStringList(java.nio.ByteBuffer buf) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (buf.remaining() < Short.BYTES) {
            return out;
        }
        int n = Short.toUnsignedInt(buf.getShort());
        for (int i = 0; i < n && buf.remaining() >= Short.BYTES; i++) {
            out.add(readString(buf));
        }
        return out;
    }

    private static String readString(java.nio.ByteBuffer buf) {
        if (buf.remaining() < Short.BYTES) {
            return "";
        }
        int len = Short.toUnsignedInt(buf.getShort());
        if (len < 0 || buf.remaining() < len) {
            return "";
        }
        byte[] data = new byte[len];
        buf.get(data);
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
