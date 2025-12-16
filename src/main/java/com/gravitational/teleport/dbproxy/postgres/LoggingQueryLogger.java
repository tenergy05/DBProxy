package com.gravitational.teleport.dbproxy.postgres;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LoggingQueryLogger writes client messages to JUL for audit/debug.
 */
public final class LoggingQueryLogger implements QueryLogger {
    private final Logger log;

    public LoggingQueryLogger() {
        this(Logger.getLogger(LoggingQueryLogger.class.getName()));
    }

    public LoggingQueryLogger(Logger log) {
        this.log = log;
    }

    @Override
    public String onQuery(String sql) {
        log.log(Level.INFO, "Query: {0}", sql);
        return sql;
    }

    @Override
    public void onParse(PgMessages.Parse parse) {
        log.log(Level.FINE, "Parse statement={0} sql={1}", new Object[]{parse.statementName, parse.query});
    }

    @Override
    public void onBind(PgMessages.Bind bind) {
        log.log(Level.FINE, "Bind statement={0} portal={1} params={2}",
            new Object[]{bind.statement, bind.portal, bind.parameterCount});
    }

    @Override
    public void onExecute(PgMessages.Execute execute) {
        log.log(Level.FINE, "Execute portal={0} maxRows={1}", new Object[]{execute.portal, execute.maxRows});
    }

    @Override
    public void onTerminate() {
        log.log(Level.FINE, "Terminate");
    }
}
