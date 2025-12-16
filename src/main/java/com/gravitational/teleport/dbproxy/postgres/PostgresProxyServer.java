package com.gravitational.teleport.dbproxy.postgres;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.gravitational.teleport.dbproxy.core.audit.AuditRecorder;
import com.gravitational.teleport.dbproxy.core.audit.DbSession;
import com.gravitational.teleport.dbproxy.core.audit.LoggingAuditRecorder;
import java.util.function.Predicate;
import java.util.Map;
import java.util.HashMap;

/**
 * PostgresProxyServer is a minimal Postgres-aware proxy that accepts client
 * connections, parses wire protocol messages for auditing/rewriting, and
 * forwards traffic to the configured backend database.
 */
public final class PostgresProxyServer implements AutoCloseable {

    private static final Logger log = Logger.getLogger(PostgresProxyServer.class.getName());

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
                    ch.pipeline().addLast("pgFrameDecoder", new PostgresFrameDecoder(true));
                    Predicate<String> jwtValidator = config.jwtValidator;
                    ch.pipeline().addLast("frontendHandler", new FrontendHandler(config.targetResolver, config.queryLogger, config.auditRecorder, session, jwtValidator));
                }
            });

        ChannelFuture bindFuture = bootstrap.bind(config.listenHost, config.listenPort).sync();
        serverChannel = bindFuture.channel();
        log.info(() -> "Postgres proxy listening on " + config.listenHost + ":" + config.listenPort);
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
        Map<String, HostPort> staticRoutes = new HashMap<>();
        TargetResolver targetResolver = (session, jwt) -> {
            if (session.getDatabaseName() != null && staticRoutes.containsKey(session.getDatabaseName())) {
                return staticRoutes.get(session.getDatabaseName());
            }
            return staticRoutes.getOrDefault("*", new HostPort("127.0.0.1", 26257));
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
        public Config addRoute(String databaseName, HostPort hostPort) {
            this.staticRoutes.put(Objects.requireNonNull(databaseName), Objects.requireNonNull(hostPort));
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
    }

    /**
     * Resolves which backend host/port to route to for a given session/JWT.
     */
    @FunctionalInterface
    public interface TargetResolver {
        HostPort resolve(DbSession session, String jwt);
    }

    /**
     * Simple holder for host/port pairs.
     */
    public record HostPort(String host, int port) {}

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
            log.log(Level.SEVERE, "Proxy stopped with error", e);
        }
    }
}
