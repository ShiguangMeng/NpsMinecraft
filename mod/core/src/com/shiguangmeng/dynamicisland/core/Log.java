package com.shiguangmeng.dynamicisland.core;

/**
 * 平台无关的日志出口。各平台层在初始化时注入（Fabric 用 slf4j，Forge 用其 Log 工具）。
 * core 不直接依赖任何日志框架，保持零外部依赖。
 */
public interface Log {
    Log NOOP = (level, message, error) -> { };

    void log(Level level, String message, Throwable error);

    default void info(String message) {
        log(Level.INFO, message, null);
    }

    default void warn(String message) {
        log(Level.WARN, message, null);
    }

    default void warn(String message, Throwable error) {
        log(Level.WARN, message, error);
    }

    default void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    enum Level {
        DEBUG, INFO, WARN
    }
}
