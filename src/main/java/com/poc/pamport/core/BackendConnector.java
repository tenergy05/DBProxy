package com.poc.pamport.core;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.Objects;
import java.util.function.Function;

/**
 * BackendConnector dials the target database using the frontend's event loop.
 * The caller supplies a pipeline initializer so DB-specific handlers can be attached.
 */
public final class BackendConnector {

    private final String host;
    private final int port;
    private final Function<Channel, ChannelInitializer<SocketChannel>> pipelineFactory;

    public BackendConnector(String host, int port, Function<Channel, ChannelInitializer<SocketChannel>> pipelineFactory) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.pipelineFactory = Objects.requireNonNull(pipelineFactory, "pipelineFactory");
    }

    public ChannelFuture connect(Channel frontendChannel) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap
            .group(frontendChannel.eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(pipelineFactory.apply(frontendChannel));
        return bootstrap.connect(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
