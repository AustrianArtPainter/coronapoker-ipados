package com.tonikelope.coronapoker.gdx;

import java.util.concurrent.atomic.AtomicInteger;

public final class SceneAssetsProbe {
    public static void main(String[] args) {
        AtomicInteger loads = new AtomicInteger(), normalizations = new AtomicInteger();
        IOSSceneAssets<Object> assets = new IOSSceneAssets<>(deck -> {
            normalizations.incrementAndGet();return deck == null ? "goliat" : deck.toLowerCase();
        }, (deck, code) -> { loads.incrementAndGet();return new Object(); });
        String[] codes = new String[52]; Object[] owned = new Object[52];
        for (int i=0;i<52;i++) {
            codes[i] = CoronaPokerGdxTable.introCardCode(i);
            owned[i] = assets.face("goliat",codes[i]);
        }
        for (int frame=0;frame<10_000;frame++) for(int i=0;i<52;i++)
            if (assets.face("goliat",codes[i]) != owned[i]) throw new AssertionError("Texture identity changed");
        if (loads.get()!=52 || normalizations.get()!=1) throw new AssertionError("Playback invoked loader/resolver");
        if (assets.face("GOLIAT",codes[0]) != owned[0] || loads.get()!=52) throw new AssertionError("Deck alias changed identity");
        if (assets.face("alternate",codes[0]) == owned[0]) throw new AssertionError("Deck selection ignored");
        if (assets.face("goliat",codes[0]) != owned[0]) throw new AssertionError("Returning to deck reloaded asset");
        assets.clear();
        if (assets.face("goliat",codes[0]) == owned[0]) throw new AssertionError("Disposed texture reference survived clear");
        System.out.println("PASS scene asset lifecycle: 52 prepared faces, 520000 reference reads, deck aliases/switches and disposal invalidation");
    }
}
