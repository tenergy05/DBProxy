package com.poc.pamport.postgres;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.pamport.core.audit.AuditRecorder;
import com.poc.pamport.core.audit.Session;
import com.poc.pamport.core.audit.LoggingAuditRecorder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgresEngine is a minimal Postgres-aware proxy that accepts client
 * connections, parses wire protocol messages for auditing/rewriting, and
 * forwards traffic to the configured backend database.
 */
public final class PostgresEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresEngine.class);

    private final Config config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public PostgresEngine(Config config) {
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
                    Session session = Session.from(ch.remoteAddress());
                    if (config.tlsContext != null) {
                        ch.pipeline().addLast("ssl", config.tlsContext.newHandler(ch.alloc()));
                    }
                    ch.pipeline().addLast("pgFrameDecoder", new PostgresFrameDecoder(true));
                    ch.pipeline().addLast("frontendHandler", new FrontendHandler(config.targetResolver, config.queryLogger, config.auditRecorder, session));
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
        TargetResolver targetResolver = session -> {
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

        public static Config fromJson(Path path) {
            try {
                ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                String json = Files.readString(path);
                FileConfig file = mapper.readValue(json, FileConfig.class);
                return fromFileConfig(file);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to load config from " + path, e);
            }
        }

        public static Config fromJson(InputStream stream) {
            try {
                ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                FileConfig file = mapper.readValue(stream, FileConfig.class);
                return fromFileConfig(file);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to load config from stream", e);
            }
        }

        public static Config fromClasspath(String resourceName) {
            InputStream in = PostgresEngine.class.getClassLoader().getResourceAsStream(resourceName);
            if (in == null) {
                throw new IllegalArgumentException("Resource not found: " + resourceName);
            }
            return fromJson(in);
        }

        private static Config fromFileConfig(FileConfig file) {
            Config cfg = new Config();
            if (file == null) {
                return cfg;
            }
            if (file.listenHost != null && !file.listenHost.isBlank()) {
                cfg.listenHost(file.listenHost);
            }
            if (file.listenPort != null) {
                cfg.listenPort(file.listenPort);
            }
            if (file.tls != null) {
                if (Boolean.TRUE.equals(file.tls.selfSigned)) {
                    cfg.tlsSelfSigned();
                } else if (file.tls.certPath != null && file.tls.keyPath != null) {
                    cfg.tls(file.tls.certPath, file.tls.keyPath);
                }
            }
            if (file.routes != null) {
                for (FileRoute route : file.routes) {
                    if (route.database == null || route.host == null || route.port == null) {
                        throw new IllegalArgumentException("route requires database, host, port");
                    }
                    cfg.addRoute(
                        route.database,
                        new Route(
                            route.host,
                            route.port,
                            route.dbUser == null ? "postgres" : route.dbUser,
                            route.dbName,
                            route.caCertPath,
                            route.serverName,
                            route.krb5CcName,
                            route.krb5ConfPath,
                            route.clientPrincipal,
                            route.servicePrincipal
                        )
                    );
                }
            }
            return cfg;
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
        Route resolve(Session session);
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

    private static final class FileTls {
        public String certPath;
        public String keyPath;
        public Boolean selfSigned;
    }

    private static final class FileRoute {
        public String database;
        public String host;
        public Integer port;
        public String dbUser;
        public String dbName;
        public String caCertPath;
        public String serverName;
        public String krb5CcName;
        public String krb5ConfPath;
        public String clientPrincipal;
        public String servicePrincipal;
    }

    private static final class FileConfig {
        public String listenHost;
        public Integer listenPort;
        public FileTls tls;
        public List<FileRoute> routes;
    }

    /**
     * Minimal launcher for local testing with defaults:
     * listen on 15432 and forward to localhost:26257 (CockroachDB default port).
     */
    public static void main(String[] args) throws Exception {
        Config config;
        if (args.length > 0) {
            config = Config.fromJson(Path.of(args[0]));
        } else {
            config = Config.fromClasspath("config.sample.json");
        }
        try (PostgresEngine server = new PostgresEngine(config)) {
            server.start();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.error("Proxy stopped with error", e);
        }
    }
}
