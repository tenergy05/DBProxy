package com.poc.pamport.dbproxy.postgres.auth;

import com.poc.pamport.dbproxy.core.BackendHandler;
import com.poc.pamport.dbproxy.core.MessagePump;
import com.poc.pamport.dbproxy.core.audit.AuditRecorder;
import com.poc.pamport.dbproxy.core.audit.DbSession;
import com.poc.pamport.dbproxy.postgres.PgMessages;
import com.poc.pamport.dbproxy.postgres.PostgresBackendAuditHandler;
import com.poc.pamport.dbproxy.postgres.PostgresFrameDecoder;
import com.poc.pamport.dbproxy.postgres.PostgresProxyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal PG backend connector with TLS (min 1.2) and GSSAPI auth using JGSS + ticket cache.
 */
public final class PgGssBackend {
    private static final Logger log = LoggerFactory.getLogger(PgGssBackend.class);
    private static final int PROTOCOL_VERSION = 196608; // 3.0

    private PgGssBackend() {}

    public static void connect(
        ChannelHandlerContext frontendCtx,
        PostgresProxyServer.Route route,
        DbSession session,
        AuditRecorder auditRecorder,
        Consumer<Channel> onSuccess,
        Consumer<Throwable> onError
    ) {
        try {
            SslContext sslContext = buildSslContext(route);
            Bootstrap bootstrap = new Bootstrap()
                .group(frontendCtx.channel().eventLoop())
                .channel(frontendCtx.channel().getClass())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        SSLEngine engine = sslContext.newEngine(ch.alloc(), route.host(), route.port());
                        engine.setUseClientMode(true);
                        ch.pipeline().addLast("ssl", new SslHandler(engine));
                        ch.pipeline().addLast("pgFrameDecoder", new PostgresFrameDecoder(false));
                        ch.pipeline().addLast("backendAudit", new PostgresBackendAuditHandler(auditRecorder, session));
                        ch.pipeline().addLast("auth", new BackendHandshakeHandler(frontendCtx, route, session, auditRecorder, onSuccess, onError));
                    }
                });
            bootstrap.connect(route.host(), route.port())
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        onError.accept(future.cause());
                    }
                });
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    private static SslContext buildSslContext(PostgresProxyServer.Route route) throws Exception {
        SslContextBuilder builder = SslContextBuilder.forClient()
            .protocols("TLSv1.2", "TLSv1.3");
        if (route.caCertPath() != null && !route.caCertPath().isBlank()) {
            builder.trustManager(new File(route.caCertPath()));
        } else {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }
        if (route.serverName() != null && !route.serverName().isBlank()) {
            builder.sslProvider(io.netty.handler.ssl.SslProvider.JDK);
        }
        return builder.build();
    }

    private static class BackendHandshakeHandler extends ChannelInboundHandlerAdapter {
        private final Channel frontend;
        private final PostgresProxyServer.Route route;
        private final DbSession session;
        private final AuditRecorder audit;
        private final Consumer<Channel> onSuccess;
        private final Consumer<Throwable> onError;
        private GSSContext gssContext;
        private boolean authOk;
        private Subject subject;

        BackendHandshakeHandler(
            ChannelHandlerContext frontendCtx,
            PostgresProxyServer.Route route,
            DbSession session,
            AuditRecorder audit,
            Consumer<Channel> onSuccess,
            Consumer<Throwable> onError
        ) {
            this.frontend = frontendCtx.channel();
            this.route = route;
            this.session = session;
            this.audit = audit;
            this.onSuccess = onSuccess;
            this.onError = onError;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.subject = loginWithRoute(route);
            ByteBuf startup = buildStartupMessage(ctx, route);
            ctx.writeAndFlush(startup);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) {
            if (!(msgObj instanceof ByteBuf msg)) {
                ctx.fireChannelRead(msgObj);
                return;
            }
            if (msg.readableBytes() < 5) {
                fail(ctx, new IllegalStateException("short message"));
                return;
            }
            char type = (char) msg.readByte();
            int length = msg.readInt();
            int payloadLength = length - Integer.BYTES;
            if (payloadLength < 0 || payloadLength > msg.readableBytes()) {
                fail(ctx, new IllegalStateException("invalid length"));
                return;
            }
            ByteBuf payload = msg.readSlice(payloadLength);
            switch (type) {
                case 'R' -> handleAuth(ctx, payload);
                case 'S', 'K', 'Z' -> {
                    frontend.writeAndFlush(msg.retainedDuplicate());
                    if (type == 'Z' && authOk) {
                        finish(ctx);
                    }
                }
                default -> frontend.writeAndFlush(msg.retainedDuplicate());
            }
        }

        private void handleAuth(ChannelHandlerContext ctx, ByteBuf payload) {
            if (payload.readableBytes() < 4) {
                fail(ctx, new IllegalStateException("invalid auth payload"));
                return;
            }
            int authType = payload.readInt();
            switch (authType) {
                case 7 -> { // AuthenticationGSS
                    byte[] token = nextGssToken(new byte[0]);
                    sendPasswordMessage(ctx, token);
                }
                case 8 -> { // AuthenticationGSSContinue
                    byte[] serverToken = new byte[payload.readableBytes()];
                    payload.readBytes(serverToken);
                    byte[] token = nextGssToken(serverToken);
                    sendPasswordMessage(ctx, token);
                }
                case 0 -> { // AuthenticationOk
                    authOk = true;
                    // forward AuthenticationOk to client
                    frontend.writeAndFlush(buildAuthOk(ctx));
                }
                default -> fail(ctx, new IllegalStateException("unsupported auth type: " + authType));
            }
        }

        private ByteBuf buildAuthOk(ChannelHandlerContext ctx) {
            ByteBuf buf = ctx.alloc().buffer(1 + 4);
            buf.writeByte((byte) 'R');
            buf.writeInt(4);
            return buf;
        }

        private byte[] nextGssToken(byte[] input) {
            try {
                return Subject.doAs(subject, (java.security.PrivilegedExceptionAction<byte[]>) () -> {
                    if (gssContext == null) {
                        GSSManager manager = GSSManager.getInstance();
                        Oid krb5Oid = new Oid("1.2.840.113554.1.2.2");
                        String service = route.servicePrincipal();
                        if (service == null || service.isBlank()) {
                            service = "postgres/" + route.host();
                        }
                        GSSName serverName = manager.createName(service, GSSName.NT_HOSTBASED_SERVICE);
                        gssContext = manager.createContext(serverName, krb5Oid, null, GSSContext.DEFAULT_LIFETIME);
                        gssContext.requestMutualAuth(true);
                    }
                    byte[] out = gssContext.initSecContext(input, 0, input.length);
                    return out == null ? new byte[0] : out;
                });
            } catch (java.security.PrivilegedActionException e) {
                Throwable cause = e.getException() == null ? e : e.getException();
                throw new IllegalStateException("GSS token generation failed", cause);
            } catch (Exception e) {
                throw new IllegalStateException("GSS token generation failed", e);
            }
        }

        private void sendPasswordMessage(ChannelHandlerContext ctx, byte[] token) {
            ByteBuf buf = ctx.alloc().buffer(1 + 4 + token.length);
            buf.writeByte((byte) 'p');
            buf.writeInt(4 + token.length);
            buf.writeBytes(token);
            ctx.writeAndFlush(buf);
        }

        private void finish(ChannelHandlerContext ctx) {
            ctx.pipeline().remove(this);
            ctx.pipeline().addLast("backendHandler", new BackendHandler(frontend));
            MessagePump.link(frontend, ctx.channel());
            onSuccess.accept(ctx.channel());
        }

        private void fail(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Backend GSS auth failed", cause);
            onError.accept(cause);
            ctx.close();
        }

        private Subject loginWithRoute(PostgresProxyServer.Route route) {
            try {
                if (route.krb5ConfPath() != null && !route.krb5ConfPath().isBlank()) {
                    System.setProperty("java.security.krb5.conf", route.krb5ConfPath());
                }
                Configuration jaas = new Configuration() {
                    @Override
                    public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                        var options = new java.util.HashMap<String, Object>();
                        options.put("useTicketCache", "true");
                        options.put("doNotPrompt", "true");
                        options.put("refreshKrb5Config", "true");
                        options.put("isInitiator", "true");
                        if (route.krb5CcName() != null && !route.krb5CcName().isBlank()) {
                            options.put("ticketCache", route.krb5CcName());
                        }
                        if (route.clientPrincipal() != null && !route.clientPrincipal().isBlank()) {
                            options.put("principal", route.clientPrincipal());
                        }
                        return new AppConfigurationEntry[] {
                            new AppConfigurationEntry(
                                "com.sun.security.auth.module.Krb5LoginModule",
                                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                options)
                        };
                    }
                };
                LoginContext lc = new LoginContext("", null, null, jaas);
                lc.login();
                return lc.getSubject();
            } catch (Exception e) {
                throw new IllegalStateException("Kerberos login failed", e);
            }
        }

        private static ByteBuf buildStartupMessage(ChannelHandlerContext ctx, PostgresProxyServer.Route route) {
            byte[] user = ("user\0" + route.dbUser() + "\0").getBytes(StandardCharsets.UTF_8);
            String dbName = route.dbName() == null ? "" : route.dbName();
            byte[] db = ("database\0" + dbName + "\0").getBytes(StandardCharsets.UTF_8);
            byte[] terminator = new byte[] {0};
            int length = 4 + 4 + user.length + db.length + terminator.length;
            ByteBuf buf = ctx.alloc().buffer(length);
            buf.writeInt(length);
            buf.writeInt(PROTOCOL_VERSION);
            buf.writeBytes(user);
            buf.writeBytes(db);
            buf.writeBytes(terminator);
            return buf;
        }
    }
}
