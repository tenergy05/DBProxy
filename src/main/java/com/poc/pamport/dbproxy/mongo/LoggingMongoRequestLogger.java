package com.poc.pamport.dbproxy.mongo;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.HexFormat;

/**
 * LoggingMongoRequestLogger dumps MongoDB frames (hex) for audit/debug.
 */
public final class LoggingMongoRequestLogger implements MongoRequestLogger {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final Logger log;
    private final boolean hexDump;

    public LoggingMongoRequestLogger() {
        this(Logger.getLogger(LoggingMongoRequestLogger.class.getName()), true);
    }

    public LoggingMongoRequestLogger(Logger log, boolean hexDump) {
        this.log = log;
        this.hexDump = hexDump;
    }

    @Override
    public void onMessage(byte[] bytes) {
        if (hexDump) {
            log.log(Level.INFO, "Mongo message {0} bytes: {1}", new Object[]{bytes.length, HEX.formatHex(bytes)});
        } else {
            log.log(Level.INFO, "Mongo message {0} bytes", bytes.length);
        }
    }
}
