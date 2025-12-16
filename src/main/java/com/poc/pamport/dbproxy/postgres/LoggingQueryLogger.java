package com.poc.pamport.dbproxy.postgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoggingQueryLogger writes client messages to SLF4J for audit/debug.
 */
public final class LoggingQueryLogger implements QueryLogger {
    private final Logger log;

    public LoggingQueryLogger() {
        this(LoggerFactory.getLogger(LoggingQueryLogger.class));
    }

    public LoggingQueryLogger(Logger log) {
        this.log = log;
    }

    @Override
    public String onQuery(String sql) {
        log.info("Query: {}", sql);
        return sql;
    }

    @Override
    public void onParse(PgMessages.Parse parse) {
        log.debug("Parse statement={} sql={}", parse.statementName, parse.query);
    }

    @Override
    public void onBind(PgMessages.Bind bind) {
        log.debug("Bind statement={} portal={} params={}", bind.statement, bind.portal, bind.parameterCount);
    }

    @Override
    public void onExecute(PgMessages.Execute execute) {
        log.debug("Execute portal={} maxRows={}", execute.portal, execute.maxRows);
    }

    @Override
    public void onTerminate() {
        log.debug("Terminate");
    }
}
