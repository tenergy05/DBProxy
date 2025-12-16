package com.gravitational.teleport.dbproxy.postgres;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class PgMessages {
    private PgMessages() {}

    interface PgMessage {}

    static final class StartupMessage implements PgMessage {
        final int major;
        final int minor;
        final Map<String, String> parameters;

        StartupMessage(int major, int minor, Map<String, String> parameters) {
            this.major = major;
            this.minor = minor;
            this.parameters = parameters;
        }

        @Override
        public String toString() {
            return "StartupMessage v" + major + "." + minor + " params=" + parameters;
        }
    }

    static final class CancelRequest implements PgMessage {
        final int backendPid;
        final int secretKey;

        CancelRequest(int backendPid, int secretKey) {
            this.backendPid = backendPid;
            this.secretKey = secretKey;
        }

        @Override
        public String toString() {
            return "CancelRequest pid=" + backendPid;
        }
    }

    static final class Query implements PgMessage {
        final String sql;

        Query(String sql) {
            this.sql = sql;
        }

        @Override
        public String toString() {
            return "Query[" + sql + "]";
        }
    }

    static final class Parse implements PgMessage {
        final String statementName;
        final String query;

        Parse(String statementName, String query) {
            this.statementName = statementName;
            this.query = query;
        }

        @Override
        public String toString() {
            return "Parse[" + statementName + "]";
        }
    }

    static final class Bind implements PgMessage {
        final String portal;
        final String statement;
        final int parameterCount;

        Bind(String portal, String statement, int parameterCount) {
            this.portal = portal;
            this.statement = statement;
            this.parameterCount = parameterCount;
        }

        @Override
        public String toString() {
            return "Bind[" + statement + "] params=" + parameterCount;
        }
    }

    static final class Execute implements PgMessage {
        final String portal;
        final int maxRows;

        Execute(String portal, int maxRows) {
            this.portal = portal;
            this.maxRows = maxRows;
        }

        @Override
        public String toString() {
            return "Execute[" + portal + "] maxRows=" + maxRows;
        }
    }

    enum Terminate implements PgMessage {
        INSTANCE
    }

    static final class PasswordMessage implements PgMessage {
        final String password;

        PasswordMessage(String password) {
            this.password = password;
        }
    }

    static final class UnknownMessage implements PgMessage {
        final char type;

        UnknownMessage(char type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return "Unknown[" + type + "]";
        }
    }

    static PgMessage parseFrontend(ByteBuf frame, boolean startupFrame) {
        ByteBuffer buffer = frame.nioBuffer(frame.readerIndex(), frame.readableBytes());
        if (startupFrame) {
            return parseStartupOrCancel(buffer);
        }

        if (buffer.remaining() < Byte.BYTES + Integer.BYTES) {
            return new UnknownMessage('?');
        }
        char type = (char) buffer.get();
        int length = buffer.getInt();
        int payloadLength = length - Integer.BYTES;
        if (payloadLength < 0 || payloadLength > buffer.remaining()) {
            return new UnknownMessage(type);
        }

        ByteBuffer payload = buffer.slice(buffer.position(), payloadLength);
        return switch (type) {
            case 'Q' -> new Query(readCString(payload));
            case 'P' -> {
                String statementName = readCString(payload);
                String sql = readCString(payload);
                yield new Parse(statementName, sql);
            }
            case 'B' -> {
                String portal = readCString(payload);
                String statement = readCString(payload);
                int formatCodeCount = Short.toUnsignedInt(payload.getShort());
                for (int i = 0; i < formatCodeCount && payload.remaining() >= Short.BYTES; i++) {
                    payload.getShort(); // ignore format codes for now.
                }
                int paramCount = Short.toUnsignedInt(payload.getShort());
                for (int i = 0; i < paramCount && payload.remaining() >= Integer.BYTES; i++) {
                    int paramLength = payload.getInt();
                    if (paramLength > 0 && paramLength <= payload.remaining()) {
                        payload.position(payload.position() + paramLength);
                    } else if (paramLength < 0) {
                        // -1 denotes NULL; nothing to skip.
                    } else {
                        payload.position(payload.limit());
                        break;
                    }
                }
                int resultFormatCount = Short.toUnsignedInt(payload.getShort());
                for (int i = 0; i < resultFormatCount && payload.remaining() >= Short.BYTES; i++) {
                    payload.getShort();
                }
                yield new Bind(portal, statement, paramCount);
            }
            case 'E' -> {
                String portal = readCString(payload);
                int maxRows = payload.remaining() >= Integer.BYTES ? payload.getInt() : 0;
                yield new Execute(portal, maxRows);
            }
            case 'X' -> Terminate.INSTANCE;
            case 'p' -> new PasswordMessage(readCString(payload));
            default -> new UnknownMessage(type);
        };
    }

    private static PgMessage parseStartupOrCancel(ByteBuffer buffer) {
        if (buffer.remaining() < Integer.BYTES * 2) {
            return new UnknownMessage('?');
        }
        int length = buffer.getInt();
        int code = buffer.getInt();
        if (code == 80877102 && buffer.remaining() >= Integer.BYTES * 2) {
            int pid = buffer.getInt();
            int secret = buffer.getInt();
            return new CancelRequest(pid, secret);
        }

        int major = (code >> 16) & 0xFFFF;
        int minor = code & 0xFFFF;
        Map<String, String> params = new LinkedHashMap<>();
        while (buffer.hasRemaining()) {
            String key = readCString(buffer);
            if (key.isEmpty()) {
                break;
            }
            String value = readCString(buffer);
            params.put(key, value);
        }
        return new StartupMessage(major, minor, params);
    }

    static ByteBuf encodeQuery(String sql, ByteBufAllocator allocator) {
        byte[] utf8 = sql.getBytes(StandardCharsets.UTF_8);
        int length = Integer.BYTES + utf8.length + 1; // length includes self, excludes leading type.
        ByteBuf buf = allocator.buffer(1 + length);
        buf.writeByte((byte) 'Q');
        buf.writeInt(length);
        buf.writeBytes(utf8);
        buf.writeByte(0);
        return buf;
    }

    static ByteBuf authenticationOk(ByteBufAllocator allocator) {
        int length = Integer.BYTES; // only length field for AuthenticationOk
        ByteBuf buf = allocator.buffer(1 + length);
        buf.writeByte((byte) 'R');
        buf.writeInt(0);
        return buf;
    }

    static ByteBuf authenticationCleartext(ByteBufAllocator allocator) {
        int length = Integer.BYTES + Integer.BYTES; // length + auth code
        ByteBuf buf = allocator.buffer(1 + length);
        buf.writeByte((byte) 'R');
        buf.writeInt(length);
        buf.writeInt(3); // AuthenticationCleartextPassword
        return buf;
    }

    static ByteBuf errorResponse(ByteBufAllocator allocator, String message) {
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        int length = Integer.BYTES + 1 + msg.length + 1 + 1; // len + 'M'+msg+\0 + terminator\0
        ByteBuf buf = allocator.buffer(1 + length);
        buf.writeByte((byte) 'E');
        buf.writeInt(length);
        buf.writeByte((byte) 'M');
        buf.writeBytes(msg);
        buf.writeByte(0);
        buf.writeByte(0); // terminator
        return buf;
    }

    private static String readCString(ByteBuffer buffer) {
        int start = buffer.position();
        int end = start;
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == 0) {
                end = buffer.position() - 1;
                break;
            }
        }
        int length = end - start;
        if (length <= 0) {
            return "";
        }
        byte[] data = new byte[length];
        buffer.position(start);
        buffer.get(data);
        if (buffer.hasRemaining()) {
            buffer.get(); // consume terminating zero
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
