package com.gravitational.teleport.dbproxy.cassandra;

import java.util.HexFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LoggingCassandraRequestLogger dumps Cassandra frames (hex) for audit/debug.
 */
public final class LoggingCassandraRequestLogger implements CassandraRequestLogger {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final Logger log;
    private final boolean hexDump;

    public LoggingCassandraRequestLogger() {
        this(Logger.getLogger(LoggingCassandraRequestLogger.class.getName()), true);
    }

    public LoggingCassandraRequestLogger(Logger log, boolean hexDump) {
        this.log = log;
        this.hexDump = hexDump;
    }

    @Override
    public void onMessage(byte[] bytes) {
        if (hexDump) {
            log.log(Level.INFO, "Cassandra message {0} bytes: {1}", new Object[]{bytes.length, HEX.formatHex(bytes)});
        } else {
            log.log(Level.INFO, "Cassandra message {0} bytes", bytes.length);
        }
    }
}
