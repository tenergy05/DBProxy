package com.poc.pamport.core.audit;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * DbSession carries per-connection metadata akin to Teleport's Session.
 */
public final class DbSession {
    private final String id;
    private final Instant startTime;
    private final String clientAddress;
    private String databaseUser;
    private String databaseName;
    private String applicationName;

    private DbSession(String id, Instant startTime, String clientAddress) {
        this.id = id;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
    }

    public static DbSession from(SocketAddress clientAddress) {
        return new DbSession(
            UUID.randomUUID().toString(),
            Instant.now(),
            clientAddress == null ? "" : clientAddress.toString());
    }

    public String getId() {
        return id;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public String getDatabaseUser() {
        return databaseUser;
    }

    public void setDatabaseUser(String databaseUser) {
        this.databaseUser = databaseUser;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
}
