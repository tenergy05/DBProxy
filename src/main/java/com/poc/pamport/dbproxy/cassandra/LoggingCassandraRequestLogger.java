package com.poc.pamport.dbproxy.cassandra;

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
        Header header = parseHeader(bytes);
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

    private static Header parseHeader(byte[] bytes) {
        if (bytes.length < 9) {
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
        return new Header(version, response, flags, streamId, opcode, bodyLength);
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case 0x00 -> "ERROR";
            case 0x01 -> "STARTUP";
            case 0x02 -> "READY";
            case 0x03 -> "AUTHENTICATE";
            case 0x04 -> "OPTIONS (v1)";
            case 0x05 -> "OPTIONS";
            case 0x06 -> "SUPPORTED";
            case 0x07 -> "QUERY";
            case 0x08 -> "RESULT";
            case 0x09 -> "PREPARE";
            case 0x0A -> "EXECUTE";
            case 0x0B -> "REGISTER";
            case 0x0C -> "EVENT";
            case 0x0D -> "BATCH";
            case 0x0E -> "AUTH_CHALLENGE";
            case 0x0F -> "AUTH_RESPONSE";
            case 0x10 -> "AUTH_SUCCESS";
            case 0x11 -> "PREPARED_ID (v5 beta)";
            default -> String.format("0x%02X", opcode);
        };
    }

    private record Header(int version, boolean response, int flags, int streamId, int opcode, int bodyLength) {}

    private static String detailForAudit(byte[] frame, Header header) {
        if (header.opcode != 0x07 && header.opcode != 0x09 && header.opcode != 0x0A && header.opcode != 0x0B && header.opcode != 0x0D) {
            return "";
        }
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame, CassandraMessages.HEADER_LENGTH, header.bodyLength());
            buf.order(java.nio.ByteOrder.BIG_ENDIAN);
            return switch (header.opcode) {
                case 0x07 -> "query=" + readLongString(buf);
                case 0x09 -> "prepare=" + readLongString(buf);
                case 0x0A -> "execute id=" + readBytesHex(buf);
                case 0x0B -> "register=" + String.join(",", readStringList(buf));
                case 0x0D -> "batch=" + parseBatch(buf);
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
