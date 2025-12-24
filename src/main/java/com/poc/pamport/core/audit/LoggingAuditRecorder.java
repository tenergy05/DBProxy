package com.poc.pamport.core.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoggingAuditRecorder serializes audit events as JSON-like maps to SLF4J.
 * This mirrors Teleport's audit surface but uses a simple logger sink here.
 */
public final class LoggingAuditRecorder implements AuditRecorder {
    private final Logger log;

    public LoggingAuditRecorder() {
        this(LoggerFactory.getLogger(LoggingAuditRecorder.class));
    }

    public LoggingAuditRecorder(Logger log) {
        this.log = log;
    }

    @Override
    public void onSessionStart(DbSession session, Throwable error) {
        Map<String, Object> payload = base(session);
        payload.put("event", error == null ? "db.session.start" : "db.session.start.error");
        if (error != null) {
            payload.put("error", error.getMessage());
        }
        log.info(payload.toString());
    }

    @Override
    public void onSessionEnd(DbSession session) {
        Map<String, Object> payload = base(session);
        payload.put("event", "db.session.end");
        log.info(payload.toString());
    }

    @Override
    public void onQuery(DbSession session, Query query) {
        Map<String, Object> payload = base(session);
        payload.put("event", query.error == null ? "db.query" : "db.query.error");
        payload.put("query", query.query);
        if (!query.parameters.isEmpty()) {
            payload.put("parameters", query.parameters);
        }
        if (query.database != null) {
            payload.put("database", query.database);
        }
        if (query.error != null) {
            payload.put("error", query.error.getMessage());
        }
        log.info(payload.toString());
    }

    @Override
    public void onResult(DbSession session, Result result) {
        Map<String, Object> payload = base(session);
        payload.put("event", result.error == null ? "db.result" : "db.result.error");
        payload.put("affected", result.affectedRecords);
        if (result.userMessage != null) {
            payload.put("message", result.userMessage);
        }
        if (result.error != null) {
            payload.put("error", result.error.getMessage());
        }
        log.info(payload.toString());
    }

    private Map<String, Object> base(DbSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.getId());
        payload.put("client", session.getClientAddress());
        payload.put("db_user", session.getDatabaseUser());
        payload.put("db_name", session.getDatabaseName());
        payload.put("app", session.getApplicationName());
        payload.put("start", session.getStartTime());
        payload.put("ts", Instant.now());
        return payload;
    }
}
