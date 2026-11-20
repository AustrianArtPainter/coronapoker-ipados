/* Copyright (C) 2026 tonikelope; GPLv3 or later. */
package com.tonikelope.coronapoker.core.game;

/** Localized text lookup required by the canonical game controller. */
@FunctionalInterface
public interface GameText {

    // The iPad renderer looks up fixed labels every frame. A fixed-arity
    // overload keeps those calls from constructing an empty varargs array.
    Object[] NO_ARGUMENTS = new Object[0];

    default String translate(String key) {
        return translate(key, NO_ARGUMENTS);
    }

    String translate(String key, Object... arguments);

    static GameText keys() {
        return (key, arguments) -> key;
    }
}
