package com.poc.pamport.dbproxy.core.audit;

import java.util.Collections;
import java.util.List;

/**
 * Query mirrors the Go struct for audit payloads.
 */
public final class Query {
    public final String query;
    public final List<String> parameters;
    public final String database;
    public final Throwable error;

    private Query(String query, List<String> parameters, String database, Throwable error) {
        this.query = query;
        this.parameters = parameters;
        this.database = database;
        this.error = error;
    }

    public static Query of(String query) {
        return new Query(query, Collections.emptyList(), null, null);
    }

    public Query withDatabase(String database) {
        return new Query(query, parameters, database, error);
    }

    public Query withParameters(List<String> parameters) {
        return new Query(query, parameters, database, error);
    }

    public Query withError(Throwable error) {
        return new Query(query, parameters, database, error);
    }
}
