package com.poc.pamport.dbproxy.postgres;

import com.poc.pamport.dbproxy.core.audit.AuditRecorder;
import com.poc.pamport.dbproxy.core.audit.DbSession;
import com.poc.pamport.dbproxy.core.audit.Result;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgresBackendAuditHandler inspects backend responses to emit OnResult audit events.
 */
public final class PostgresBackendAuditHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(PostgresBackendAuditHandler.class);

    private final AuditRecorder audit;
    private final DbSession session;

    public PostgresBackendAuditHandler(AuditRecorder audit, DbSession session) {
        this.audit = audit;
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        ByteBuf copy = msg.retainedDuplicate();
        try {
            if (copy.readableBytes() < 5) {
                return;
            }
            char type = (char) copy.readByte();
            int length = copy.readInt();
            int payloadLength = length - Integer.BYTES;
            if (payloadLength < 0 || payloadLength > copy.readableBytes()) {
                return;
            }

            switch (type) {
                case 'C' -> auditCommandComplete(copy.readSlice(payloadLength));
                case 'E' -> auditError(copy.readSlice(payloadLength));
                default -> {
                    // ignore other messages
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse backend message for audit", e);
        } finally {
            ReferenceCountUtil.safeRelease(copy);
            ctx.fireChannelRead(msg.retain());
        }
    }

    private void auditCommandComplete(ByteBuf payload) {
        String commandTag = readCString(payload);
        long rows = parseRowsAffected(commandTag);
        audit.onResult(session, Result.ok(rows));
    }

    private void auditError(ByteBuf payload) {
        String message = parseErrorMessage(payload);
        audit.onResult(session, Result.error(new RuntimeException(message)));
    }

    private static String parseErrorMessage(ByteBuf payload) {
        String msg = "";
        while (payload.isReadable()) {
            byte fieldType = payload.readByte();
            if (fieldType == 0) {
                break;
            }
            String value = readCString(payload);
            if (fieldType == 'M') {
                msg = value;
            }
        }
        return msg;
    }

    private static String readCString(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = start;
        while (buf.isReadable()) {
            byte b = buf.readByte();
            if (b == 0) {
                break;
            }
            end++;
        }
        int length = end - start;
        if (length <= 0) {
            return "";
        }
        byte[] data = new byte[length];
        buf.getBytes(start, data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static long parseRowsAffected(String commandTag) {
        if (commandTag == null || commandTag.isEmpty()) {
            return 0;
        }
        try {
            String[] parts = commandTag.split(" ");
            String last = parts[parts.length - 1];
            return Long.parseLong(last);
        } catch (Exception e) {
            return 0;
        }
    }
}
