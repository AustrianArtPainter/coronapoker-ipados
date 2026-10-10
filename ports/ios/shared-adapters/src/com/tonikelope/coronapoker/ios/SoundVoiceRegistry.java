package com.tonikelope.coronapoker.ios;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Playback tokens are unique even when the native audio pool reuses a slot. */
public final class SoundVoiceRegistry<S> {
    private final Map<Integer, Voice<S>> slots = new HashMap<>();
    private long nextToken = 1L;

    public synchronized long claim(Object owner, int nativeId, S source) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(source);
        if (nativeId <= 0) return -1L;
        if (nextToken == Long.MAX_VALUE) throw new IllegalStateException("Playback token space exhausted");
        long token = nextToken++;
        // Replacing the slot retires the previous token and its owner. Storage
        // remains bounded by the native source pool, including finished cues.
        slots.put(nativeId, new Voice<>(owner, token, source));
        return token;
    }

    public synchronized boolean apply(Object owner, long token, Consumer<S> action) {
        if (token < 0) return false;
        for (Voice<S> voice : slots.values()) {
            if (voice.owner == owner && voice.token == token) {
                action.accept(voice.source);
                return true;
            }
        }
        return false;
    }

    public synchronized void applyAll(Object owner, Consumer<S> action) {
        for (Voice<S> voice : slots.values()) if (voice.owner == owner) action.accept(voice.source);
    }

    public synchronized void releaseAll(Object owner, Consumer<S> stop) {
        var iterator = slots.values().iterator();
        while (iterator.hasNext()) {
            Voice<S> voice = iterator.next();
            if (voice.owner == owner) {
                stop.accept(voice.source);
                iterator.remove();
            }
        }
    }

    public synchronized int size() { return slots.size(); }

    private static final class Voice<S> {
        final Object owner;
        final long token;
        final S source;
        Voice(Object owner, long token, S source) { this.owner = owner; this.token = token; this.source = source; }
    }
}
