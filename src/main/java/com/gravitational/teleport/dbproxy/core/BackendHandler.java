package com.gravitational.teleport.dbproxy.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BackendHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = Logger.getLogger(BackendHandler.class.getName());

    private final Channel frontend;

    public BackendHandler(Channel frontend) {
        this.frontend = frontend;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        frontend.writeAndFlush(msg.retain());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        MessagePump.closeOnFlush(frontend);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.log(Level.WARNING, "Backend connection failed", cause);
        MessagePump.closeOnFlush(frontend);
    }
}
