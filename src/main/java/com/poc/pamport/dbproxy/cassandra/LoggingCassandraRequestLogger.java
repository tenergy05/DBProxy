package com.poc.pamport.dbproxy.cassandra;

import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoggingCassandraRequestLogger dumps Cassandra frames (hex) for audit/debug.
 */
public final class LoggingCassandraRequestLogger implements CassandraRequestLogger {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final Logger log;
    private final boolean hexDump;

    public LoggingCassandraRequestLogger() {
        this(LoggerFactory.getLogger(LoggingCassandraRequestLogger.class), true);
    }

    public LoggingCassandraRequestLogger(Logger log, boolean hexDump) {
        this.log = log;
        this.hexDump = hexDump;
    }

    @Override
    public void onMessage(byte[] bytes) {
        if (hexDump) {
            log.info("Cassandra message {} bytes: {}", bytes.length, HEX.formatHex(bytes));
        } else {
            log.info("Cassandra message {} bytes", bytes.length);
        }
    }
}
