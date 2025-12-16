package com.poc.pamport.dbproxy.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.buffer.Unpooled;

public final class MessagePump {
    private MessagePump() {}

    public static void link(Channel frontend, Channel backend) {
        frontend.closeFuture().addListener((ChannelFutureListener) f -> closeQuietly(backend));
        backend.closeFuture().addListener((ChannelFutureListener) f -> closeQuietly(frontend));
    }

    public static void closeOnFlush(Channel ch) {
        if (ch == null || !ch.isOpen()) {
            return;
        }
        ChannelPromise promise = ch.newPromise();
        ch.writeAndFlush(Unpooled.EMPTY_BUFFER, promise);
        promise.addListener(ChannelFutureListener.CLOSE);
    }

    public static void closeQuietly(Channel ch) {
        if (ch != null && ch.isOpen()) {
            ch.close();
        }
    }
}
