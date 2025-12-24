package com.poc.pamport.cassandra;

import com.poc.pamport.core.audit.AuditRecorder;
import com.poc.pamport.core.audit.DbSession;
import com.poc.pamport.core.audit.Query;
import com.poc.pamport.cassandra.protocol.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks a single Cassandra frontend/backend pair during handshake.
 */
final class CassandraHandshakeState {
    private static final Logger log = LoggerFactory.getLogger(CassandraHandshakeState.class);

    private final CassandraEngine.Config config;
    private final CassandraGssAuthenticator gss;
    private final CassandraRequestLogger requestLogger;
    private final AuditRecorder auditRecorder;
    private Channel frontend;
    private Channel backend;
    private volatile boolean ready;
    private int protocolVersion = -1;
    private final List<ByteBuf> pending = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private DbSession session;
    private boolean sessionStarted;

    CassandraHandshakeState(CassandraEngine.Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.gss = new CassandraGssAuthenticator(config);
        this.requestLogger = config.requestLogger;
        this.auditRecorder = config.auditRecorder;
    }

    void frontend(Channel channel) {
        this.frontend = channel;
    }

    Channel frontend() {
        return frontend;
    }

    void backend(Channel channel) {
        this.backend = channel;
    }

    CassandraGssAuthenticator gss() {
        return gss;
    }

    boolean isReady() {
        return ready;
    }

    void markReady() {
        this.ready = true;
        startSession(null);
    }

    void ensureProtocolVersion(int version) {
        if (protocolVersion == -1) {
            protocolVersion = version;
        }
    }

    int protocolVersion() {
        return protocolVersion == -1 ? 4 : protocolVersion;
    }

    void addPending(ByteBuf buf) {
        pending.add(buf.retain());
    }

    void flushPending() {
        Channel ch = backend;
        if (ch == null) {
            return;
        }
        for (ByteBuf buf : pending) {
            ch.write(buf);
        }
        pending.clear();
        ch.flush();
    }

    void forwardToFrontend(ByteBuf msg) {
        Channel ch = frontend;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(msg.retain());
        } else {
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    void closeBoth() {
        if (closed.compareAndSet(false, true)) {
            if (frontend != null) {
                frontend.close();
            }
            if (backend != null) {
                backend.close();
            }
            for (ByteBuf buf : pending) {
                ReferenceCountUtil.safeRelease(buf);
            }
            pending.clear();
            endSession();
        }
    }

    void sendAuthResponse(ChannelHandlerContext ctx, Protocol.Header header, byte[] token) {
        ByteBuf buf = ctx.alloc().buffer(Protocol.HEADER_LENGTH + Integer.BYTES + token.length);
        byte versionByte = (byte) (protocolVersion() & 0x7F); // request direction
        buf.writeByte(versionByte);
        buf.writeByte(0); // flags
        buf.writeShort(header.streamId());
        buf.writeByte(Protocol.OPCODE_AUTH_RESPONSE);
        buf.writeInt(Integer.BYTES + token.length);
        buf.writeInt(token.length);
        buf.writeBytes(token);
        ctx.writeAndFlush(buf);
    }

    void session(DbSession session) {
        this.session = session;
    }

    DbSession session() {
        return session;
    }

    void startSession(Throwable error) {
        if (sessionStarted || session == null) {
            return;
        }
        sessionStarted = true;
        auditRecorder.onSessionStart(session, error);
    }

    void endSession() {
        if (sessionStarted && session != null) {
            auditRecorder.onSessionEnd(session);
        }
    }

    void onQuery(Protocol.ParsedMessage parsed) {
        if (parsed == null || session == null) {
            return;
        }
        auditRecorder.onQuery(session, Query.of(parsed.detail()));
    }

    void fail(Throwable error) {
        startSession(error);
        closeBoth();
    }
}
