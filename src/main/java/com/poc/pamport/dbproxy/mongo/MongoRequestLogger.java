package com.poc.pamport.dbproxy.mongo;

/**
 * Hook for MongoDB requests. Currently receives raw byte payloads for custom inspection.
 */
public interface MongoRequestLogger {
    default void onMessage(byte[] bytes) {}

    MongoRequestLogger NO_OP = new MongoRequestLogger() {};
}
