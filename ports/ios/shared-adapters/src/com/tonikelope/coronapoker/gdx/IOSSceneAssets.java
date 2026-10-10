package com.tonikelope.coronapoker.gdx;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Render-thread asset references. The table retains texture/disposal ownership. */
final class IOSSceneAssets<T> {
    private final Function<String, String> normalize;
    private final BiFunction<String, String, T> load;
    private final Map<String, Deck<T>> requested = new HashMap<>();
    private final Map<String, Deck<T>> decks = new HashMap<>();

    IOSSceneAssets(Function<String, String> normalize, BiFunction<String, String, T> load) {
        this.normalize = normalize;
        this.load = load;
    }

    T face(String deck, String code) {
        Deck<T> assets = requested.get(deck);
        if (assets == null) {
            String canonical = normalize.apply(deck);
            assets = decks.get(canonical);
            if (assets == null) {
                assets = new Deck<>(canonical);
                decks.put(canonical, assets);
            }
            requested.put(deck, assets);
        }
        T face = assets.faces.get(code);
        if (face != null) return face;
        face = load.apply(assets.canonical, code);
        assets.faces.put(code, face);
        return face;
    }

    void clear() { requested.clear(); decks.clear(); }

    private static final class Deck<T> {
        final String canonical;
        final Map<String, T> faces = new HashMap<>();
        Deck(String canonical) { this.canonical = canonical; }
    }
}
