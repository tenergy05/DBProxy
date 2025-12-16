package com.gravitational.teleport.dbproxy.core.audit;

import java.net.SocketAddress;

/**
 * AuditRecorder mirrors Teleport's database audit surface in a slimmed-down form.
 */
public interface AuditRecorder {
    void onSessionStart(DbSession session, Throwable error);
    void onSessionEnd(DbSession session);
    void onQuery(DbSession session, Query query);
    void onResult(DbSession session, Result result);

    /**
     * Convenience to start a new session context with client address.
     */
    default DbSession newSession(SocketAddress clientAddress) {
        return DbSession.from(clientAddress);
    }
}
