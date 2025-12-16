package com.poc.pamport.dbproxy.cassandra;

import com.poc.pamport.dbproxy.core.BackendConnector;
import com.poc.pamport.dbproxy.core.BackendHandler;
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
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CassandraProxyServer is a placeholder proxy for Cassandra native protocol.
 */
public final class CassandraProxyServer implements AutoCloseable {
    private static final Logger log = Logger.getLogger(CassandraProxyServer.class.getName());

    private final Config config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public CassandraProxyServer(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start() throws InterruptedException {
        if (serverChannel != null) {
            throw new IllegalStateException("already started");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        Function<Channel, ChannelInitializer<SocketChannel>> backendPipeline =
            frontendChannel -> new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast("cassandraFrameDecoder", new CassandraFrameDecoder());
                    ch.pipeline().addLast("backendHandler", new BackendHandler(frontendChannel));
                }
            };
        BackendConnector connector = new BackendConnector(config.targetHost, config.targetPort, backendPipeline);

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast("cassandraFrameDecoder", new CassandraFrameDecoder());
                    ch.pipeline().addLast("frontendHandler", new CassandraFrontendHandler(connector, config.requestLogger));
                }
            });

        ChannelFuture bindFuture = bootstrap.bind(config.listenHost, config.listenPort).sync();
        serverChannel = bindFuture.channel();
        log.info(() -> "Cassandra proxy listening on " + config.listenHost + ":" + config.listenPort
            + " -> " + connector);
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
    }

    public static void main(String[] args) throws InterruptedException {
        Config config = new Config();
        try (CassandraProxyServer server = new CassandraProxyServer(config)) {
            server.start();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Cassandra proxy stopped with error", e);
        }
    }
}
