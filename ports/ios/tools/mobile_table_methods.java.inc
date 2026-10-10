
    private boolean mobileResumeRequired;
    private boolean mobileScreenshotViewerPending;
    private long mobileScreenshotRequest;
    private void mobileCaptureAndViewScreenshot() {
        if (disposed || mobileResumeRequired || mobileScreenshotViewerPending
                || finalExitPending || uiLayer != UI_NONE) return;
        mobileScreenshotViewerPending = true;
        mobileScreenshotRequest++;
        screenshotRequested = true;
        if (screenshotSound != null && tablePreference("sonido_screenshot", true)) {
            play(screenshotSound, 0.92f, 1f);
        }
    }
    private void mobileScreenshotWritten(long request, boolean saved) {
        if (!mobileScreenshotViewerPending || request != mobileScreenshotRequest) return;
        mobileScreenshotViewerPending = false;
        if (saved && !disposed && !mobileResumeRequired && !finalExitPending
                && uiLayer == UI_NONE) openScreenshotViewer();
    }
    private final com.tonikelope.coronapoker.ios.TouchTextHold mobileTextHold =
            new com.tonikelope.coronapoker.ios.TouchTextHold();
    private int mobileTextHoldTarget;
    private void mobileUpdateTextHold() {
        if (!mobileTextHold.ready(Gdx.input.getX(), Gdx.input.getY(),
                Gdx.input.isTouched(0), System.nanoTime())) return;
        if (isClientTransportReconnecting() || blocksTableUtilities(activeDialog)
                || tableIdentityDialog != null || tableImageClearConfirmation) return;
        pointer.set(Gdx.input.getX(), Gdx.input.getY());
        viewport.unproject(pointer);
        if (mobileTextHoldTarget == 1 && uiLayer == UI_CHAT
                && !chatImageMode && chatPointerSelectionDragging) {
            chatEdit.focus("tableChat", chatDraft);
            if (!chatEdit.hasSelection(chatDraft)) chatEdit.selectAll(chatDraft);
            chatPointerSelectionDragging = false;
            openTableChatEditMenu(pointer.x, pointer.y);
        } else if (mobileTextHoldTarget == 2 && uiLayer == UI_GAME_LOG
                && gameLogSelectionDragging) {
            gameLogSelectionDragging = false;
            openGameLogEditMenu(pointer.x, pointer.y);
        }
    }
    void mobileSuspendInput() {
        if (mobilePendingShuffleBarrier != null) mobilePendingShuffleBarrier.complete(null);
        mobilePendingShuffle = null;
        mobilePendingShuffleBarrier = null;
        mobileTextHold.cancel();
        mobileScroll.cancel();
        mobileUiPressBlocked = false;
        clearPointerRepeat();
        chatPointerSelectionDragging = gameLogSelectionDragging = false;
        quickChatScrollDragging = gameLogScrollDragging = false;
        settingsDebugScrollDragging = settingsRowScrollDragging = false;
        mobileScreenshotRequest++;
        mobileScreenshotViewerPending = false;
        screenshotRequested = false;
        // No queued AUTO choice may place a wager after returning from lock.
        queuedPreAction = 0;
        autoActionControlsReady = false;
        autoActionEligibleAt = Float.POSITIVE_INFINITY;
        armedHudTarget = 0;
        mobileResumeRequired = true;
        if (activeDialog != null && activeDialog.isAutoAction()) activeDialog.dismiss();
        if (voiceRecorder != null) finishVoiceRecording(true);
        com.tonikelope.coronapoker.ios.IOSKeyboard.visible(false);
    }

    void mobileRestore(GdxTableViewState latest,
            TableVisualEvent.PreparationStatus.Phase phase, String deck) {
        mobileResumeRequired = false;
        livePositionRotation = null;
        liveActionChips.clear();
        pendingCollectBets = null;
        liveChipBatch = null;
        livePayout = null;
        liveRebuy = null;
        liveInitialStackFill = null;
        if (liveCinematic != null) { liveCinematic.animation.dispose(); liveCinematic = null; }
        if (liveShuffle != null) {
            // The table retains all preloaded shuffle textures across resume.
            liveShuffle = null;
        }
        if (shuffleSound != null) shuffleSound.stop();
        liveCardFlights.clear();
        liveHoleDealCount = 0;
        liveHoleSwap = null;
        liveCommunityReveal = null;
        liveRabbitReveal = null;
        liveHoleReveal = null;
        liveHoleFold = null;
        liveAllInRunoutPause = null;
        for (LiveAudioPlayback playback : List.copyOf(liveAudioCueWaits)) finishAudioCue(playback, null);
        pendingLocalActionKind = null;
        pendingLocalActionControlsSequence = -1;
        livePotContributions.clear();
        liveShowdownInvestments.clear();
        liveWinnerStarts.clear();
        liveHandProbabilities.clear();
        allInActionSoundsPlayed.clear();
        liveState = latest;
        preparationPhase = phase;
        if (deck != null) liveDeck = deck;
        if (phase == TableVisualEvent.PreparationStatus.Phase.READY) remoteSeatEntryStartedAt = totalTime;
        syncSeatsFromLiveState();
    }
