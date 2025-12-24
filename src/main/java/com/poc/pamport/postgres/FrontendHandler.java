package com.poc.pamport.postgres;

import com.poc.pamport.core.MessagePump;
import com.poc.pamport.core.audit.AuditRecorder;
import com.poc.pamport.core.audit.Session;
import com.poc.pamport.core.audit.Query;
import com.poc.pamport.postgres.auth.PgGssBackend;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FrontendHandler consumes messages from Postgres clients, parses them for
 * logging/rewriting hooks, and forwards the raw frames to the backend.
 */
final class FrontendHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(FrontendHandler.class);

    private final QueryLogger queryLogger;
    private final AuditRecorder auditRecorder;
    private final Session session;
    private final PostgresEngine.TargetResolver targetResolver;
    private final List<ByteBuf> pending = new ArrayList<>();
    private Channel backend;
    private boolean startupSeen;
    private boolean sessionStarted;

    FrontendHandler(PostgresEngine.TargetResolver targetResolver, QueryLogger queryLogger, AuditRecorder auditRecorder, Session session) {
        super(false); // Disable auto-release; we manage ByteBuf lifecycle manually
        this.targetResolver = targetResolver;
        this.queryLogger = queryLogger;
        this.auditRecorder = auditRecorder;
        this.session = session;
        this.session.setProtocol("postgres");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        boolean inStartupPhase = !startupSeen;
        PgMessages.PgMessage parsed = PgMessages.parseFrontend(msg, inStartupPhase);
        ByteBuf outbound = msg;
        boolean reuseOriginal = true;
        boolean shouldForward = true;

        if (parsed == PgMessages.SSLRequest.INSTANCE || parsed == PgMessages.GSSENCRequest.INSTANCE) {
            ctx.writeAndFlush(PgMessages.sslNotSupported(ctx.alloc()));
            ReferenceCountUtil.safeRelease(msg);
            return;
        } else if (parsed instanceof PgMessages.StartupMessage startup) {
            startupSeen = true;
            session.setDatabaseUser(startup.parameters.get("user"));
            session.setDatabaseName(startup.parameters.get("database"));
            session.setApplicationName(startup.parameters.get("application_name"));
            session.setUserAgent(startup.parameters.get("application_name"));
            session.setStartupParameters(startup.parameters);
        } else if (parsed instanceof PgMessages.CancelRequest) {
            startupSeen = true;
        } else if (parsed instanceof PgMessages.PasswordMessage password) {
            // Client password is ignored; proxy owns backend auth.
            ReferenceCountUtil.release(msg);
            return;
        } else if (parsed instanceof PgMessages.Query query) {
            String rewritten = queryLogger.onQuery(query.sql);
            if (!rewritten.equals(query.sql)) {
                outbound = PgMessages.encodeQuery(rewritten, ctx.alloc());
                reuseOriginal = false;
            }
            auditRecorder.onQuery(session, Query.of(rewritten).withDatabase(session.getDatabaseName()));
        } else if (parsed instanceof PgMessages.Parse parse) {
            queryLogger.onParse(parse);
        } else if (parsed instanceof PgMessages.Bind bind) {
            queryLogger.onBind(bind);
        } else if (parsed instanceof PgMessages.Execute execute) {
            queryLogger.onExecute(execute);
        } else if (parsed == PgMessages.Terminate.INSTANCE) {
            queryLogger.onTerminate();
        }

        if (backend == null) {
            if (shouldForward) {
                // Pass ownership to pending list
                pending.add(reuseOriginal ? msg : outbound);
            } else if (reuseOriginal) {
                ReferenceCountUtil.release(msg);
            }
            maybeConnect(ctx);
            return;
        }

        if (shouldForward) {
            // Pass ownership to channel
            backend.writeAndFlush(reuseOriginal ? msg : outbound);
        } else if (reuseOriginal) {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releasePending();
        MessagePump.closeOnFlush(backend);
        auditRecorder.onSessionEnd(session);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Frontend connection failed", cause);
        releasePending();
        MessagePump.closeOnFlush(backend);
        ctx.close();
    }

    private void maybeConnect(ChannelHandlerContext ctx) {
        if (backend != null) {
            return;
        }
        PostgresEngine.Route route = targetResolver.resolve(session);
        if (route == null) {
            ctx.writeAndFlush(PgMessages.errorResponse(ctx.alloc(), "No route for database"))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        session.setDatabaseService(route.dbName() != null ? route.dbName() : route.host());
        session.setDatabaseType("postgres");
        session.setDatabaseProtocol("postgres");
        PgGssBackend.connect(ctx, route, session, auditRecorder, client -> {
            backend = client;
            MessagePump.link(ctx.channel(), backend);
            if (!sessionStarted) {
                sessionStarted = true;
                auditRecorder.onSessionStart(session, null);
            }
            flushPending();
        }, error -> {
            log.warn("Failed to connect/authenticate to backend", error);
            if (!sessionStarted) {
                sessionStarted = true;
                auditRecorder.onSessionStart(session, error);
            }
            ctx.writeAndFlush(PgMessages.errorResponse(ctx.alloc(), "Backend connection failed"))
                .addListener(ChannelFutureListener.CLOSE);
        });
    }

    private void flushPending() {
        if (backend == null || pending.isEmpty()) {
            return;
        }
        for (ByteBuf buf : pending) {
            backend.write(buf);
        }
        pending.clear();
        backend.flush();
    }

    private void releasePending() {
        for (ByteBuf buf : pending) {
            ReferenceCountUtil.safeRelease(buf);
        }
        pending.clear();
    }
}
