package com.gravitational.teleport.dbproxy.cassandra;

/**
 * Hook for Cassandra requests. Currently receives raw byte payloads for custom inspection.
 */
public interface CassandraRequestLogger {
    default void onMessage(byte[] bytes) {}

    CassandraRequestLogger NO_OP = new CassandraRequestLogger() {};
}
