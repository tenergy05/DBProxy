package com.poc.pamport.dbproxy.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HexFormat;

/**
 * LoggingMongoRequestLogger dumps MongoDB frames (hex) for audit/debug.
 */
public final class LoggingMongoRequestLogger implements MongoRequestLogger {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final Logger log;
    private final boolean hexDump;

    public LoggingMongoRequestLogger() {
        this(LoggerFactory.getLogger(LoggingMongoRequestLogger.class), true);
    }

    public LoggingMongoRequestLogger(Logger log, boolean hexDump) {
        this.log = log;
        this.hexDump = hexDump;
    }

    @Override
    public void onMessage(byte[] bytes) {
        if (hexDump) {
            log.info("Mongo message {} bytes: {}", bytes.length, HEX.formatHex(bytes));
        } else {
            log.info("Mongo message {} bytes", bytes.length);
        }
    }
}
