package com.poc.pamport.dbproxy.cassandra;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

/**
 * Backend pipeline wiring for Cassandra: frame decoder + auth/forwarder handler.
 */
final class CassandraBackendPipelineInitializer extends ChannelInitializer<SocketChannel> {
    private final CassandraHandshakeState state;

    CassandraBackendPipelineInitializer(CassandraHandshakeState state) {
        this.state = state;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast("cassandraFrameDecoder", new CassandraFrameDecoder());
        ch.pipeline().addLast("cassandraBackendHandler", new CassandraBackendHandler(state));
    }
}
