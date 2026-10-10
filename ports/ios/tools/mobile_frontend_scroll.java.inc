
    private final com.tonikelope.coronapoker.ios.TouchScrollGesture mobileScroll =
            new com.tonikelope.coronapoker.ios.TouchScrollGesture();
    private int mobileScrollTarget;
    private Surface mobileScrollSurface;
    private void mobileBeginContentScroll(int sx, int sy, int p, int button) {
        if (mobileScroll == null || button != Input.Buttons.LEFT || p != 0
                || editMenu != null || dropdown != Dropdown.NONE
                || scrollDrag != ScrollDrag.NONE) return;
        int target = 0;
        if (surface == Surface.SETTINGS && isSettingsRootModal()
                && !settingsRestartNotice) {
            GdxSettingsLayout.Frame frame = GdxSettingsLayout.frame(
                    viewport.getWorldWidth(), viewport.getWorldHeight(),
                    settingsSections().size(), settingsSubpageLabels().size());
            if (settingsRowScrollMaximum > 0f
                    && pointer.x >= frame.content().x
                    && pointer.x < settingsRowScrollTrack.x
                    && pointer.y >= settingsRowScrollTrack.y
                    && pointer.y <= settingsRowScrollTrack.y + settingsRowScrollTrack.height) target = 1;
            if (settingsSession.section() == GdxSettingsContract.Section.DEBUG
                    && settingsDebugScrollMaximum > 0f
                    && pointer.x >= frame.content().x && pointer.x < settingsDebugScrollTrack.x
                    && pointer.y >= settingsDebugScrollTrack.y
                    && pointer.y <= settingsDebugScrollTrack.y + settingsDebugScrollTrack.height) target = 2;
        } else if (surface == Surface.LOBBY && !hasBlockingFrontendModal()
                && !lobbyImageMode && !lobbyEmojiPickerOpen && lobbyChatScrollMaximum > 0f
                && lobbyChatViewport.contains(pointer)) target = 3;
        else if (surface == Surface.STATS) {
            if (statsPicker != StatsPicker.NONE && statsPickerScrollMaximum > 0f
                    && pointer.x >= 520f && pointer.x < statsPickerScrollTrack.x
                    && pointer.y >= STATS_PICKER_VIEW_BOTTOM && pointer.y <= STATS_PICKER_VIEW_TOP) target = 4;
            else if (!hasBlockingFrontendModal() || isFrontendRootModal()) {
                if (statsChartZoom > 1f && statsChartViewport.contains(pointer)) target = 5;
                else if (statsResultScrollMaximum > 0f
                        && pointer.x >= mobileStatsResultsLeft() && pointer.x < statsResultScrollTrack.x
                        && pointer.y >= statsResultScrollTrack.y
                        && pointer.y <= statsResultScrollTrack.y + statsResultScrollTrack.height) target = 6;
                else if (statsGameIndex >= 0 && statsHandIndex < 0
                        && pointer.x >= 86f && pointer.x <= 434f
                        && pointer.y >= 323f && pointer.y <= 395f) target = 7;
            }
        }
        if (target != 0 && mobileScroll.begin(p, sx, sy, pointer.x, pointer.y, target == 5)) {
            mobileScrollTarget = target; mobileScrollSurface = surface;
        }
    }
    private boolean mobileMoveContentScroll(int sx, int sy, int p) {
        if (mobileScroll == null || !mobileScroll.active()) return false;
        if (!mobileContentScrollValid()) {
            mobileScroll.cancel(); pressedHit = null; clearPointerRepeat();
            return true;
        }
        if (!mobileScroll.owns(p)) return true;
        pointer.set(sx, sy); viewport.unproject(pointer);
        if (!mobileScroll.move(p, sx, sy, pointer.x, pointer.y)) return false;
        mobileTextHold.cancel(); pressedHit = null; clearPointerRepeat(); pointerSelectionField = null;
        float dy = mobileScroll.deltaY();
        switch (mobileScrollTarget) {
            case 1 -> {
                if (settingsSession.section() == GdxSettingsContract.Section.AUDIO)
                    settingsAudioScroll = mobileOffset(settingsAudioScroll, dy, settingsRowScrollMaximum);
                else if (settingsSession.section() == GdxSettingsContract.Section.APPEARANCE)
                    settingsAppearanceScroll = mobileOffset(settingsAppearanceScroll, dy, settingsRowScrollMaximum);
                else if (settingsSession.section() == GdxSettingsContract.Section.SHORTCUTS)
                    settingsShortcutScroll = mobileOffset(settingsShortcutScroll, dy, settingsRowScrollMaximum);
            }
            case 2 -> settingsDebugScroll = mobileOffset(settingsDebugScroll, -dy, settingsDebugScrollMaximum);
            case 3 -> lobbyChatScroll = mobileOffset(lobbyChatScroll, -dy, lobbyChatScrollMaximum);
            case 4 -> statsPickerScroll = statsPickerScrollTarget = mobileOffset(statsPickerScroll, dy, statsPickerScrollMaximum);
            case 5 -> {
                statsChartPanX = statsChartPanTargetX = mobileOffset(statsChartPanX,
                        -mobileScroll.deltaX() / Math.max(1f, statsChartViewport.width * (statsChartZoom - 1f)), 1f);
                statsChartPanY = statsChartPanTargetY = mobileOffset(statsChartPanY,
                        dy / Math.max(1f, statsChartViewport.height * (statsChartZoom - 1f)), 1f);
            }
            case 6 -> statsResultScroll = statsResultScrollTarget = mobileOffset(statsResultScroll, dy, statsResultScrollMaximum);
            case 7 -> statsSummaryPlayersScroll = statsSummaryPlayersScrollTarget = mobileOffset(
                    statsSummaryPlayersScrollTarget, dy, statsSummaryPlayersMaximum(statsGames.get(statsGameIndex).players().size()));
            default -> { }
        }
        return true;
    }
    private boolean mobileEndContentScroll(int sx, int sy, int p) {
        if (mobileScroll == null || !mobileScroll.active()) return false;
        if (!mobileScroll.owns(p)) return true;
        if (!mobileContentScrollValid()) {
            mobileScroll.cancel(); pressedHit = null; clearPointerRepeat();
            return true;
        }
        mobileMoveContentScroll(sx, sy, p);
        boolean dragged = mobileScroll.dragging(); mobileScroll.cancel();
        if (dragged) { pressedHit = null; pointerSelectionField = null; clearPointerRepeat(); }
        return dragged;
    }
    private boolean mobileContentScrollValid() {
        if (surface != mobileScrollSurface || editMenu != null || dropdown != Dropdown.NONE) return false;
        return switch (mobileScrollTarget) {
            case 1, 2 -> isSettingsRootModal() && !settingsRestartNotice;
            case 3 -> !hasBlockingFrontendModal() && !lobbyImageMode && !lobbyEmojiPickerOpen;
            case 4 -> statsPicker != StatsPicker.NONE;
            case 5, 6, 7 -> !hasBlockingFrontendModal() || isFrontendRootModal();
            default -> false;
        };
    }
    private float mobileStatsResultsLeft() {
        // Match the result table drawn for each mode, excluding adjacent charts.
        if (statsMode == StatsMode.BEST_HANDS) return 1163f;
        if (statsMode == StatsMode.PERFORMANCE) return 1043f;
        if (statsMode == StatsMode.BALANCE) return statsHandIndex < 0 ? 1298f : 523f;
        return 528f;
    }
    private static float mobileOffset(float value, float delta, float maximum) {
        return com.tonikelope.coronapoker.ios.TouchScrollGesture.offset(value, delta, maximum);
    }
