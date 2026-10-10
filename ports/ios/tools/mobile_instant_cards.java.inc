    private record MobileProbeSound(float due, Sound sound, float volume, float pitch) {}
    private final java.util.ArrayList<MobileProbeSound> mobileProbeSounds = new java.util.ArrayList<>();
    private void mobileProbeSound(Sound sound, float volume, float pitch, float delay) {
        mobileProbeSounds.add(new MobileProbeSound(totalTime + delay, sound, volume, pitch));
    }
    private void mobileUpdateProbeSounds() {
        for (int i=mobileProbeSounds.size()-1; i>=0; i--) {
            MobileProbeSound cue = mobileProbeSounds.get(i);
            if (totalTime >= cue.due()) {
                mobileProbeSounds.remove(i); play(cue.sound(), cue.volume(), cue.pitch());
                com.tonikelope.coronapoker.ios.InstantCardProbe.SOUNDS.incrementAndGet();
            }
        }
    }
    private boolean mobileAcceptInstantCard(TableVisualEvent event, CompletableFuture<Void> barrier) {
        if (!com.tonikelope.coronapoker.ios.InstantCardProbe.ENABLED) return false;
        if (event instanceof TableVisualEvent.DealHoleCard deal) {
            int order = liveHoleDealCount++;
            if (liveDealSoundEnabled()) mobileProbeSound(dealSound, 0.30f, 0.96f+(order%3)*0.025f, 0f);
            boolean own = deal.nickname().equals(liveState.snapshot().localNickname());
            if (deal.card().faceUp() && !deal.card().code().isBlank()
                    && (own ? liveOwnHoleFlipSoundEnabled() : liveFlipSoundEnabled()))
                mobileProbeSound(uncoverSound, 0.45f, 1f,
                        liveDealFlightSeconds(activeDealtPlayerCount(), liveDealSpeedPercent()));
        } else if (event instanceof TableVisualEvent.DealCommunityCard deal) {
            if (liveDealSoundEnabled()) mobileProbeSound(dealSound, 0.34f, 0.94f+deal.slot()*0.018f, 0f);
        } else if (event instanceof TableVisualEvent.RevealCommunityCards reveal) {
            if (liveFlipSoundEnabled())
                for (int i=0; i<reveal.cards().size(); i++)
                    mobileProbeSound(uncoverSound, 0.48f, 1f,
                            communityRevealStartSeconds(i, reveal.leadInMillis()/1000f));
        } else if (event instanceof TableVisualEvent.RevealHoleCards) {
            if (liveFlipSoundEnabled()) mobileProbeSound(uncoverSound, 0.54f, 1f, 0f);
        } else if (event instanceof TableVisualEvent.FoldHoleCards) {
            if (liveFoldSoundEnabled()) mobileProbeSound(foldSound, 0.58f, 1f, 0f);
        } else if (!(event instanceof TableVisualEvent.SwapHoleCards)
                && !(event instanceof TableVisualEvent.RabbitCards)) {
            return false;
        }
        // No LiveCardFlight/LiveCommunityReveal/LiveHoleReveal is created.
        // Existing render-state handlers commit the final card placement immediately.
        liveState.apply(event); syncSeatsFromLiveState();
        com.tonikelope.coronapoker.ios.InstantCardProbe.EVENTS.incrementAndGet();
        barrier.complete(null); return true;
    }
