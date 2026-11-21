package com.shiguangmeng.dynamicisland.platform;

import com.shiguangmeng.dynamicisland.core.Log;

/** Platform-neutral log sink: every loader ships slf4j-api on the class path. */
public final class Slf4jLog implements Log {

    private final org.slf4j.Logger delegate;
    private final boolean debugEnabled;

    public Slf4jLog(String name, boolean debugEnabled) {
        this.delegate = org.slf4j.LoggerFactory.getLogger(name);
        this.debugEnabled = debugEnabled;
    }

    @Override
    public void log(Level level, String message, Throwable error) {
        // 刻意不用 switch 表达式：1.16.5 目标要编成 Java 8 字节码
        if (level == Level.DEBUG) {
            if (debugEnabled) {
                delegate.info(message, error);
            }
        } else if (level == Level.WARN) {
            delegate.warn(message, error);
        } else {
            delegate.info(message, error);
        }
    }
}
