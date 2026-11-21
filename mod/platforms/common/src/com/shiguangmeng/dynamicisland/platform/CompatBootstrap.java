package com.shiguangmeng.dynamicisland.platform;

import com.shiguangmeng.dynamicisland.core.Compat;

/**
 * Picks the version shim at runtime.
 *
 * <p>Only the shim that matches the running Minecraft version is compiled into a given jar, so the
 * candidates are looked up reflectively and the loader simply takes the first one that resolves.
 * That keeps one bootstrap class working for every version group while each jar still carries
 * exactly one implementation.
 */
public final class CompatBootstrap {

    /** Version shims, newest first. Exactly one of them exists in any given build. */
    private static final String[] CANDIDATES = {
            "com.shiguangmeng.dynamicisland.compat.Compat1211",
            "com.shiguangmeng.dynamicisland.compat.Compat1215",
            "com.shiguangmeng.dynamicisland.compat.Compat1214",
            "com.shiguangmeng.dynamicisland.compat.Compat1206",
            "com.shiguangmeng.dynamicisland.compat.Compat120",
            "com.shiguangmeng.dynamicisland.compat.Compat119",
            "com.shiguangmeng.dynamicisland.compat.Compat118",
            "com.shiguangmeng.dynamicisland.compat.Compat116",
    };

    private CompatBootstrap() {
    }

    /** Installs the first shim that can be loaded; leaves the no-op shim in place if none can. */
    public static void install() {
        for (String name : CANDIDATES) {
            try {
                Class<?> type = Class.forName(name, true, CompatBootstrap.class.getClassLoader());
                Compat.install((Compat) type.getDeclaredConstructor().newInstance());
                return;
            } catch (Throwable ignored) {
                // 这个版本组的类没打进包，或与当前 MC 版本不匹配，继续找下一个
            }
        }
    }
}
