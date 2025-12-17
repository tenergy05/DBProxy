package com.poc.pamport.dbproxy.postgres;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import com.poc.pamport.dbproxy.core.audit.AuditRecorder;
import com.poc.pamport.dbproxy.core.audit.DbSession;
import com.poc.pamport.dbproxy.core.audit.LoggingAuditRecorder;
import java.util.function.Predicate;
import java.util.Map;
import java.util.HashMap;

/**
 * PostgresProxyServer is a minimal Postgres-aware proxy that accepts client
 * connections, parses wire protocol messages for auditing/rewriting, and
 * forwards traffic to the configured backend database.
 */
public final class PostgresProxyServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresProxyServer.class);

    private final Config config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public PostgresProxyServer(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start() throws InterruptedException {
        if (serverChannel != null) {
            throw new IllegalStateException("already started");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    DbSession session = DbSession.from(ch.remoteAddress());
                    if (config.tlsContext != null) {
                        ch.pipeline().addLast("ssl", config.tlsContext.newHandler(ch.alloc()));
                    }
                    ch.pipeline().addLast("pgFrameDecoder", new PostgresFrameDecoder(true));
                    Predicate<String> jwtValidator = config.jwtValidator;
                    ch.pipeline().addLast("frontendHandler", new FrontendHandler(config.targetResolver, config.queryLogger, config.auditRecorder, session, jwtValidator));
                }
            });

        ChannelFuture bindFuture = bootstrap.bind(config.listenHost, config.listenPort).sync();
        serverChannel = bindFuture.channel();
        log.info("Postgres proxy listening on {}:{}", config.listenHost, config.listenPort);
    }

    public void blockUntilShutdown() throws InterruptedException {
        Channel channel = serverChannel;
        if (channel != null) {
            channel.closeFuture().sync();
        }
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * Simple configuration holder for the proxy.
     */
    public static final class Config {
        String listenHost = "0.0.0.0";
        int listenPort = 15432;
        Map<String, Route> staticRoutes = new HashMap<>();
        TargetResolver targetResolver = (session, jwt) -> {
            if (session.getDatabaseName() != null && staticRoutes.containsKey(session.getDatabaseName())) {
                return staticRoutes.get(session.getDatabaseName());
            }
            return staticRoutes.getOrDefault(
                "*",
                new Route("127.0.0.1", 26257, "postgres", session.getDatabaseName(),
                    null, null, null, null, null, null));
        };
        QueryLogger queryLogger = new LoggingQueryLogger();
        AuditRecorder auditRecorder = new LoggingAuditRecorder();
        Predicate<String> jwtValidator = token -> true; // replace with real JWT validation

        public Config listenHost(String listenHost) {
            this.listenHost = listenHost;
            return this;
        }

        public Config listenPort(int listenPort) {
            this.listenPort = listenPort;
            return this;
        }

        public Config targetResolver(TargetResolver resolver) {
            this.targetResolver = Objects.requireNonNull(resolver);
            return this;
        }

        /**
         * Add a static route keyed by database name; use "*" for default.
         */
        public Config addRoute(String databaseName, Route route) {
            this.staticRoutes.put(Objects.requireNonNull(databaseName), Objects.requireNonNull(route));
            return this;
        }

        public Config queryLogger(QueryLogger queryLogger) {
            this.queryLogger = Objects.requireNonNull(queryLogger);
            return this;
        }

        public Config auditRecorder(AuditRecorder auditRecorder) {
            this.auditRecorder = Objects.requireNonNull(auditRecorder);
            return this;
        }

        public Config jwtValidator(Predicate<String> jwtValidator) {
            this.jwtValidator = Objects.requireNonNull(jwtValidator);
            return this;
        }

        private SslContext tlsContext;

        public Config tls(String certPath, String keyPath) {
            try {
                if (certPath == null || certPath.isBlank() || keyPath == null || keyPath.isBlank()) {
                    this.tlsContext = null;
                    return this;
                }
                this.tlsContext = SslContextBuilder.forServer(new java.io.File(certPath), new java.io.File(keyPath))
                    .protocols("TLSv1.2", "TLSv1.3")
                    .build();
                return this;
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to build TLS context", e);
            }
        }

        public Config tlsSelfSigned() {
            try {
                SelfSignedCertificate ssc = new SelfSignedCertificate();
                this.tlsContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                    .protocols("TLSv1.2", "TLSv1.3")
                    .build();
                return this;
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to build self-signed TLS context", e);
            }
        }
    }

    /**
     * Resolves which backend host/port to route to for a given session/JWT.
     */
    @FunctionalInterface
    public interface TargetResolver {
        Route resolve(DbSession session, String jwt);
    }

    /**
     * Backend route with connection credentials.
     */
        public record Route(
            String host,
            int port,
            String dbUser,
            String dbName,
            String caCertPath,
            String serverName,
            String krb5CcName,
            String krb5ConfPath,
            String clientPrincipal,
            String servicePrincipal
        ) {}

    /**
     * Minimal launcher for local testing with defaults:
     * listen on 15432 and forward to localhost:26257 (CockroachDB default port).
     */
    public static void main(String[] args) throws InterruptedException {
        Config config = new Config();
        try (PostgresProxyServer server = new PostgresProxyServer(config)) {
            server.start();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.error("Proxy stopped with error", e);
        }
    }
}
