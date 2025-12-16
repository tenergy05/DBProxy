package com.poc.pamport.dbproxy.postgres;

import com.poc.pamport.dbproxy.core.BackendHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.poc.pamport.dbproxy.core.BackendConnector;
import com.poc.pamport.dbproxy.core.MessagePump;
import com.poc.pamport.dbproxy.core.audit.AuditRecorder;
import com.poc.pamport.dbproxy.core.audit.DbSession;
import com.poc.pamport.dbproxy.core.audit.Query;
import java.util.function.Predicate;

/**
 * FrontendHandler consumes messages from Postgres clients, parses them for
 * logging/rewriting hooks, and forwards the raw frames to the backend.
 */
final class FrontendHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = Logger.getLogger(FrontendHandler.class.getName());

    private final QueryLogger queryLogger;
    private final AuditRecorder auditRecorder;
    private final DbSession session;
    private final Predicate<String> jwtValidator;
    private final PostgresProxyServer.TargetResolver targetResolver;
    private final List<ByteBuf> pending = new ArrayList<>();
    private BackendConnector connector;
    private Channel backend;
    private boolean startupSeen;
    private String jwt;

    FrontendHandler(PostgresProxyServer.TargetResolver targetResolver, QueryLogger queryLogger, AuditRecorder auditRecorder, DbSession session, Predicate<String> jwtValidator) {
        this.targetResolver = targetResolver;
        this.queryLogger = queryLogger;
        this.auditRecorder = auditRecorder;
        this.session = session;
        this.jwtValidator = jwtValidator;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        PgMessages.PgMessage parsed = PgMessages.parseFrontend(msg, !startupSeen);
        startupSeen = true;

        ByteBuf outbound = msg;
        boolean reuseOriginal = true;

        if (parsed instanceof PgMessages.StartupMessage startup) {
            session.setDatabaseUser(startup.parameters.get("user"));
            session.setDatabaseName(startup.parameters.get("database"));
            session.setApplicationName(startup.parameters.get("application_name"));
        } else if (parsed instanceof PgMessages.PasswordMessage password) {
            jwt = password.password;
            if (!jwtValidator.test(jwt)) {
                ctx.writeAndFlush(PgMessages.errorResponse(ctx.alloc(), "Invalid credentials"));
                ctx.close();
                return;
            }
            auditRecorder.onSessionStart(session, null);
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
            pending.add(reuseOriginal ? outbound.retain() : outbound);
            maybeConnect(ctx);
            return;
        }

        backend.writeAndFlush(reuseOriginal ? outbound.retain() : outbound);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releasePending();
        MessagePump.closeOnFlush(backend);
        auditRecorder.onSessionEnd(session);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.log(Level.WARNING, "Frontend connection failed", cause);
        releasePending();
        MessagePump.closeOnFlush(backend);
        ctx.close();
    }

    private void maybeConnect(ChannelHandlerContext ctx) {
        if (backend != null) {
            return;
        }
        PostgresProxyServer.HostPort hp = targetResolver.resolve(session, jwt);
        if (hp == null) {
            ctx.writeAndFlush(PgMessages.errorResponse(ctx.alloc(), "No route for database"))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        connector = new BackendConnector(hp.host(), hp.port(), frontendChannel -> new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel backendCh) {
                backendCh.pipeline().addLast("pgFrameDecoder", new PostgresFrameDecoder(false));
                backendCh.pipeline().addLast("backendAudit", new PostgresBackendAuditHandler(auditRecorder, session));
                backendCh.pipeline().addLast("backendHandler", new BackendHandler(frontendChannel));
            }
        });

        connector.connect(ctx.channel())
            .addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.log(Level.WARNING, "Failed to connect to backend", future.cause());
                    auditRecorder.onSessionStart(session, future.cause());
                    ctx.close();
                    return;
                }
                backend = future.channel();
                MessagePump.link(ctx.channel(), backend);
                flushPending();
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
