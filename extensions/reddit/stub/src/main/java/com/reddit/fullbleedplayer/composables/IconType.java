package com.reddit.fullbleedplayer.composables;

import kotlin.jvm.internal.DefaultConstructorMarker;

/**
 * The real class is a Kotlin enum with an abstract obfuscated {@code toIcon} method.
 * Declared here as a regular abstract class so the extension can subclass it.
 */
public abstract class IconType {
    public static IconType Download;

    public IconType(String name, int ordinal, DefaultConstructorMarker marker) {
        throw new UnsupportedOperationException("Stub");
    }

    public static IconType[] values() {
        throw new UnsupportedOperationException("Stub");
    }
}
