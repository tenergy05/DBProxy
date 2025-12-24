package com.poc.pamport.dbproxy.cassandra;

import com.poc.pamport.dbproxy.core.audit.AuditRecorder;
import com.poc.pamport.dbproxy.core.audit.LoggingAuditRecorder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CassandraEngine mirrors Teleport's naming: a listener that terminates SASL/GSS on behalf of clients
 * and forwards Cassandra native protocol thereafter.
 */
public class CassandraEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CassandraEngine.class);

    protected final Config config;
    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workerGroup;
    protected Channel serverChannel;

    public CassandraEngine(Config config) {
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
                    ch.pipeline().addLast("cassandraFrameDecoder", new CassandraFrameDecoder());
                    ch.pipeline().addLast("frontendHandler", new CassandraFrontendHandler(config));
                }
            });

        ChannelFuture bindFuture = bootstrap.bind(config.listenHost, config.listenPort).sync();
        serverChannel = bindFuture.channel();
        log.info("Cassandra engine listening on {}:{} -> {}:{}", config.listenHost, config.listenPort, config.targetHost, config.targetPort);
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

    public static final class Config {
        String listenHost = "0.0.0.0";
        int listenPort = 19042;
        String targetHost = "127.0.0.1";
        int targetPort = 9042;
        CassandraRequestLogger requestLogger = new LoggingCassandraRequestLogger();
        AuditRecorder auditRecorder = new LoggingAuditRecorder();
        String servicePrincipal;
        String krb5ConfPath;
        String krb5CcName;
        String clientPrincipal;

        public Config listenHost(String listenHost) {
            this.listenHost = listenHost;
            return this;
        }

        public Config listenPort(int listenPort) {
            this.listenPort = listenPort;
            return this;
        }

        public Config targetHost(String targetHost) {
            this.targetHost = targetHost;
            return this;
        }

        public Config targetPort(int targetPort) {
            this.targetPort = targetPort;
            return this;
        }

        public Config requestLogger(CassandraRequestLogger requestLogger) {
            this.requestLogger = Objects.requireNonNull(requestLogger);
            return this;
        }

        public Config auditRecorder(AuditRecorder auditRecorder) {
            this.auditRecorder = Objects.requireNonNull(auditRecorder);
            return this;
        }

        public Config servicePrincipal(String servicePrincipal) {
            this.servicePrincipal = servicePrincipal;
            return this;
        }

        public Config krb5ConfPath(String krb5ConfPath) {
            this.krb5ConfPath = krb5ConfPath;
            return this;
        }

        public Config krb5CcName(String krb5CcName) {
            this.krb5CcName = krb5CcName;
            return this;
        }

        public Config clientPrincipal(String clientPrincipal) {
            this.clientPrincipal = clientPrincipal;
            return this;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Config config = new Config();
        try (CassandraEngine server = new CassandraEngine(config)) {
            server.start();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.error("Cassandra engine stopped with error", e);
        }
    }
}
