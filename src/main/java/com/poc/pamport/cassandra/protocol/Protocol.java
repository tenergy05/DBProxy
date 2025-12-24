package com.poc.pamport.cassandra.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Protocol constants and helpers mirroring Teleport's cassandra/protocol helpers.
 * Extended to parse STARTUP and AUTH_RESPONSE for robust client handling.
 */
public final class Protocol {
    public static final int HEADER_LENGTH = 9;

    public static final int OPCODE_ERROR = 0x00;
    public static final int OPCODE_STARTUP = 0x01;
    public static final int OPCODE_READY = 0x02;
    public static final int OPCODE_AUTHENTICATE = 0x03;
    public static final int OPCODE_SUPPORTED = 0x06;
    public static final int OPCODE_QUERY = 0x07;
    public static final int OPCODE_RESULT = 0x08;
    public static final int OPCODE_PREPARE = 0x09;
    public static final int OPCODE_EXECUTE = 0x0A;
    public static final int OPCODE_REGISTER = 0x0B;
    public static final int OPCODE_EVENT = 0x0C;
    public static final int OPCODE_BATCH = 0x0D;
    public static final int OPCODE_AUTH_CHALLENGE = 0x0E;
    public static final int OPCODE_AUTH_RESPONSE = 0x0F;
    public static final int OPCODE_AUTH_SUCCESS = 0x10;
    public static final int OPCODE_OPTIONS = 0x05;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private Protocol() {}

    public static Packet parsePacket(ByteBuf frame) {
        Header header = parseHeader(frame);
        if (header == null) {
            return null;
        }
        return new Packet(header);
    }

    public static Header parseHeader(ByteBuf frame) {
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
        return new Header(version, response, flags, streamId, opcode, bodyLength);
    }

    public static byte[] copy(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    public static byte[] readAuthToken(ByteBuf frame, int bodyLength) {
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

    /**
     * Parse STARTUP message to extract options (CQL_VERSION, COMPRESSION, DRIVER_NAME, etc).
     * Returns null if not a valid STARTUP message.
     */
    public static StartupMessage parseStartup(ByteBuf frame) {
        Header header = parseHeader(frame);
        if (header == null || header.opcode() != OPCODE_STARTUP) {
            return null;
        }
        if (header.bodyLength() < Short.BYTES) {
            return new StartupMessage(Map.of());
        }
        ByteBuf body = frame.slice(frame.readerIndex() + HEADER_LENGTH, header.bodyLength());
        Map<String, String> options = readStringMap(body);
        return new StartupMessage(options);
    }

    /**
     * Parse AUTH_RESPONSE to extract credentials.
     * Cassandra PasswordAuthenticator format: \0username\0password (NUL-delimited).
     */
    public static AuthResponseMessage parseAuthResponse(ByteBuf frame) {
        Header header = parseHeader(frame);
        if (header == null || header.opcode() != OPCODE_AUTH_RESPONSE) {
            return null;
        }
        byte[] token = readAuthToken(frame, header.bodyLength());
        if (token.length == 0) {
            return new AuthResponseMessage(null, null, token);
        }
        // Parse PasswordAuthenticator format: \0username\0password
        return parsePasswordAuthToken(token);
    }

    /**
     * Parse PasswordAuthenticator token format: \0username\0password
     */
    private static AuthResponseMessage parsePasswordAuthToken(byte[] token) {
        if (token.length < 3) {
            return new AuthResponseMessage(null, null, token);
        }
        // Token format: [0x00][username bytes][0x00][password bytes]
        // Find first NUL
        int firstNul = -1;
        for (int i = 0; i < token.length; i++) {
            if (token[i] == 0) {
                firstNul = i;
                break;
            }
        }
        if (firstNul < 0) {
            // No NUL found - might be a different authenticator format
            return new AuthResponseMessage(null, null, token);
        }

        // Find second NUL (after username)
        int secondNul = -1;
        for (int i = firstNul + 1; i < token.length; i++) {
            if (token[i] == 0) {
                secondNul = i;
                break;
            }
        }

        String username;
        String password;
        if (secondNul < 0) {
            // Only one NUL - might be: [0x00][username][0x00][password] with password going to end
            // or: [username][0x00][password]
            if (firstNul == 0) {
                // Format: [0x00][rest] - username is between first NUL and end or second NUL
                // Actually the standard format is: \0username\0password
                // So we look for the pattern differently
                int usernameEnd = -1;
                for (int i = 1; i < token.length; i++) {
                    if (token[i] == 0) {
                        usernameEnd = i;
                        break;
                    }
                }
                if (usernameEnd > 1) {
                    username = new String(token, 1, usernameEnd - 1, StandardCharsets.UTF_8);
                    if (usernameEnd + 1 < token.length) {
                        password = new String(token, usernameEnd + 1, token.length - usernameEnd - 1, StandardCharsets.UTF_8);
                    } else {
                        password = "";
                    }
                    return new AuthResponseMessage(username, password, token);
                }
            }
            // Fallback: treat everything after first NUL as username, no password
            username = new String(token, firstNul + 1, token.length - firstNul - 1, StandardCharsets.UTF_8);
            password = null;
        } else {
            // Two NULs found
            if (firstNul == 0) {
                // Format: [0x00][username][0x00][password]
                username = new String(token, 1, secondNul - 1, StandardCharsets.UTF_8);
                password = new String(token, secondNul + 1, token.length - secondNul - 1, StandardCharsets.UTF_8);
            } else {
                // Format: [authzid][0x00][username][0x00][password] (SASL style)
                username = new String(token, firstNul + 1, secondNul - firstNul - 1, StandardCharsets.UTF_8);
                password = new String(token, secondNul + 1, token.length - secondNul - 1, StandardCharsets.UTF_8);
            }
        }
        return new AuthResponseMessage(username, password, token);
    }

    /**
     * Parse AUTHENTICATE message to extract authenticator class name.
     */
    public static String parseAuthenticateClass(ByteBuf frame) {
        Header header = parseHeader(frame);
        if (header == null || header.opcode() != OPCODE_AUTHENTICATE) {
            return null;
        }
        if (header.bodyLength() < Short.BYTES) {
            return "";
        }
        ByteBuf body = frame.slice(frame.readerIndex() + HEADER_LENGTH, header.bodyLength());
        return readString(body);
    }

    public static ParsedMessage parseForAudit(ByteBuf frame) {
        Header header = parseHeader(frame);
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

    private static Map<String, String> readStringMap(ByteBuf body) {
        Map<String, String> map = new HashMap<>();
        if (body.readableBytes() < Short.BYTES) {
            return map;
        }
        int count = body.readUnsignedShort();
        for (int i = 0; i < count && body.readableBytes() >= Short.BYTES; i++) {
            String key = readString(body);
            String value = readString(body);
            if (key != null && value != null) {
                map.put(key, value);
            }
        }
        return map;
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

    // ---- Record types ----

    public record Header(int version, boolean response, int flags, int streamId, int opcode, int bodyLength) {}

    public record ParsedMessage(String kind, String detail) {}

    public record Packet(Header header) {}

    /**
     * Parsed STARTUP message with options.
     */
    public record StartupMessage(Map<String, String> options) {
        public String cqlVersion() {
            return options.getOrDefault("CQL_VERSION", "3.0.0");
        }

        public String compression() {
            return options.get("COMPRESSION");
        }

        public String driverName() {
            return options.get("DRIVER_NAME");
        }

        public String driverVersion() {
            return options.get("DRIVER_VERSION");
        }
    }

    /**
     * Parsed AUTH_RESPONSE message with credentials.
     */
    public record AuthResponseMessage(String username, String password, byte[] rawToken) {
        public boolean hasCredentials() {
            return username != null && !username.isEmpty();
        }
    }
}
