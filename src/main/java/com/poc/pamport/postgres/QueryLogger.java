package com.poc.pamport.postgres;

/**
 * QueryLogger is a hook for auditing or rewriting client requests before they
 * are forwarded to the backend Postgres/CockroachDB server.
 */
public interface QueryLogger {

    /**
     * Called for Query messages ('Q').
     *
     * @param sql original SQL string
     * @return SQL string to forward (return the same value to leave untouched)
     */
    default String onQuery(String sql) {
        return sql;
    }

    /**
     * Called when a Parse message ('P') is seen.
     */
    default void onParse(PgMessages.Parse parse) {}

    /**
     * Called when a Bind message ('B') is seen.
     */
    default void onBind(PgMessages.Bind bind) {}

    /**
     * Called when an Execute message ('E') is seen.
     */
    default void onExecute(PgMessages.Execute execute) {}

    /**
     * Called when the client terminates the session ('X').
     */
    default void onTerminate() {}

    QueryLogger NO_OP = new QueryLogger() {};
}
