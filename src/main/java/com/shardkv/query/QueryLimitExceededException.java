package com.shardkv.query;

public class QueryLimitExceededException extends RuntimeException {

    public QueryLimitExceededException(int limit) {
        super("Query result exceeds configured maximum of " + limit);
    }
}
