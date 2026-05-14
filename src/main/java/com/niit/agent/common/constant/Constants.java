package com.niit.agent.common.constant;

public class Constants {

    public static final String TOKEN_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String REDIS_SESSION_KEY = "chat:session:";
    public static final String REDIS_CONTEXT_KEY = "chat:context:";
    public static final String REDIS_USER_KEY = "chat:user:";

    public static final int MAX_CONTEXT_ROUNDS = 8;
    public static final int MAX_CONTEXT_LENGTH = 2000;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_ADMIN = "admin";

    public static final String SCOPE_SESSION = "session";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_GLOBAL = "global";
}
