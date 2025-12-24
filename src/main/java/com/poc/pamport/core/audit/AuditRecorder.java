package com.poc.pamport.core.audit;

import java.net.SocketAddress;

/**
 * AuditRecorder mirrors Teleport's database audit surface in a slimmed-down form.
 */
public interface AuditRecorder {
    void onSessionStart(Session session, Throwable error);
    void onSessionEnd(Session session);
    void onQuery(Session session, Query query);
    void onResult(Session session, Result result);

    /**
     * Convenience to start a new session context with client address.
     */
    default Session newSession(SocketAddress clientAddress) {
        return Session.from(clientAddress);
    }
}
