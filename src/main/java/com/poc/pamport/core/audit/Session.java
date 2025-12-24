package com.poc.pamport.core.audit;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * Session carries per-connection metadata akin to Teleport's common.Session.
 */
public final class Session {
    private final String id;
    private final Instant startTime;
    private final String clientAddress;
    // Teleport-like fields (simplified types to avoid external deps)
    private String clusterName;
    private String hostId;
    private String databaseService;
    private String databaseType;
    private String databaseProtocol;
    private String identityUser;
    private String autoCreateUserMode;
    private java.util.List<String> databaseRoles = java.util.Collections.emptyList();
    private java.util.Map<String, String> startupParameters = java.util.Collections.emptyMap();
    private java.util.List<String> lockTargets = java.util.Collections.emptyList();
    private long postgresPid;
    private String userAgent;
    private String databaseUser;
    private String databaseName;
    private String applicationName;
    private String protocol;

    private Session(String id, Instant startTime, String clientAddress) {
        this.id = id;
        this.startTime = startTime;
        this.clientAddress = clientAddress;
    }

    public static Session from(SocketAddress clientAddress) {
        return new Session(
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

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public String getDatabaseService() {
        return databaseService;
    }

    public void setDatabaseService(String databaseService) {
        this.databaseService = databaseService;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(String databaseType) {
        this.databaseType = databaseType;
    }

    public String getDatabaseProtocol() {
        return databaseProtocol;
    }

    public void setDatabaseProtocol(String databaseProtocol) {
        this.databaseProtocol = databaseProtocol;
    }

    public String getIdentityUser() {
        return identityUser;
    }

    public void setIdentityUser(String identityUser) {
        this.identityUser = identityUser;
    }

    public String getAutoCreateUserMode() {
        return autoCreateUserMode;
    }

    public void setAutoCreateUserMode(String autoCreateUserMode) {
        this.autoCreateUserMode = autoCreateUserMode;
    }

    public java.util.List<String> getDatabaseRoles() {
        return databaseRoles;
    }

    public void setDatabaseRoles(java.util.List<String> databaseRoles) {
        this.databaseRoles = databaseRoles == null ? java.util.Collections.emptyList() : databaseRoles;
    }

    public java.util.Map<String, String> getStartupParameters() {
        return startupParameters;
    }

    public void setStartupParameters(java.util.Map<String, String> startupParameters) {
        this.startupParameters = startupParameters == null ? java.util.Collections.emptyMap() : startupParameters;
    }

    public java.util.List<String> getLockTargets() {
        return lockTargets;
    }

    public void setLockTargets(java.util.List<String> lockTargets) {
        this.lockTargets = lockTargets == null ? java.util.Collections.emptyList() : lockTargets;
    }

    public long getPostgresPid() {
        return postgresPid;
    }

    public void setPostgresPid(long postgresPid) {
        this.postgresPid = postgresPid;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
