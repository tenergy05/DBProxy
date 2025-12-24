package com.poc.pamport.mongo;

import com.poc.pamport.core.BackendConnector;
import com.poc.pamport.core.BackendHandler;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoEngine is a placeholder proxy for MongoDB wire protocol.
 * It frames messages using the length field and forwards bytes to the backend.
 */
public final class MongoEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MongoEngine.class);

    private final Config config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public MongoEngine(Config config) {
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
                    ch.pipeline().addLast("mongoFrameDecoder", new MongoFrameDecoder());
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
                    ch.pipeline().addLast("mongoFrameDecoder", new MongoFrameDecoder());
                    ch.pipeline().addLast("frontendHandler", new MongoFrontendHandler(connector, config.requestLogger));
                }
            });

        ChannelFuture bindFuture = bootstrap.bind(config.listenHost, config.listenPort).sync();
        serverChannel = bindFuture.channel();
        log.info("Mongo proxy listening on {}:{} -> {}", config.listenHost, config.listenPort, connector);
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
        int listenPort = 27018;
        String targetHost = "127.0.0.1";
        int targetPort = 27017;
        MongoRequestLogger requestLogger = new LoggingMongoRequestLogger();

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

        public Config requestLogger(MongoRequestLogger requestLogger) {
            this.requestLogger = Objects.requireNonNull(requestLogger);
            return this;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Config config = new Config();
        try (MongoEngine server = new MongoEngine(config)) {
            server.start();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.error("Mongo proxy stopped with error", e);
        }
    }
}
