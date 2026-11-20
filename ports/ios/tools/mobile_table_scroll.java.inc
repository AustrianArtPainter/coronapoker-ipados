
    private final com.tonikelope.coronapoker.ios.TouchScrollGesture mobileScroll =
            new com.tonikelope.coronapoker.ios.TouchScrollGesture();
    private int mobileScrollTarget, mobileScrollLayer;
    private boolean mobileUiPressBlocked;
    private void mobileBeginContentScroll(int sx, int sy, int p, int button) {
        if (mobileScroll == null || button != Input.Buttons.LEFT || p != 0
                || isClientTransportReconnecting() || tableGallery.isOpen()
                || tableIdentityDialog != null || blocksTableUtilities(activeDialog)
                || voiceNotesOpen || recoveryStopBarrier != null) return;
        pointer.set(sx, sy); viewport.unproject(pointer);
        int target = 0;
        if (uiLayer == UI_SETTINGS) {
            SettingsRowScroll rows = settingsRowScroll();
            GdxSettingsLayout.Frame frame = GdxSettingsLayout.frame(
                    viewport.getWorldWidth(), viewport.getWorldHeight(),
                    settingsSession.sections().size(), settingsSubpageLabels().size());
            if (rows != null && pointer.x >= frame.content().x
                    && pointer.x < rows.x() - GdxSettingsLayout.SCROLLBAR_HIT_WIDTH / 2f
                    && pointer.y >= rows.rows().viewportBottom()
                    && pointer.y <= rows.rows().viewportBottom() + rows.rows().viewportHeight()) target = 1;
            else if (settingsSection() == GdxSettingsContract.Section.DEBUG
                    && settingsDebugArea().viewport().contains(pointer)
                    && !settingsDebugTrackContains(pointer.x, pointer.y)
                    && mobileSettingsDebugMaximumScroll() > 0f) target = 2;
        } else if (uiLayer == UI_CHAT && !tableGallery.isOpen() && !chatEditMenuOpen
                && quickChatScrollMaximum > 0f && quickChatHistoryBounds().contains(pointer)
                && !quickChatScrollTrackContains(pointer.x, pointer.y)) target = 3;
        else if (uiLayer == UI_GAME_LOG && !gameLogEditMenuOpen
                && gameLogMaximumScroll() > 0f && gameLogContentBounds().contains(pointer)
                && !gameLogTrackContains(pointer.x, pointer.y)) target = 4;
        else if (uiLayer == UI_NETWORK_BLOCKS) {
            GdxNetworkBlockDialogLayout.Layout layout = GdxNetworkBlockDialogLayout.layout(
                    viewport.getWorldWidth(), viewport.getWorldHeight());
            if (layout.rows().contains(pointer)
                    && GdxNetworkBlockDialogLayout.maximumScroll(activeNetworkBlocks().size(), layout.rows()) > 0f
                    && !networkBlocksScrollTrackContains(pointer.x, pointer.y)) target = 5;
        }
        if (target != 0 && mobileScroll.begin(p, sx, sy, pointer.x, pointer.y)) {
            mobileScrollTarget = target; mobileScrollLayer = uiLayer;
            mobileUiPressBlocked = true;
        }
    }
    private boolean mobileMoveContentScroll(int sx, int sy, int p) {
        if (mobileScroll == null || !mobileScroll.active()) return false;
        if (!mobileContentScrollValid()) { mobileScroll.cancel(); return true; }
        if (!mobileScroll.owns(p)) return true;
        pointer.set(sx, sy); viewport.unproject(pointer);
        if (!mobileScroll.move(p, sx, sy, pointer.x, pointer.y)) return false;
        mobileTextHold.cancel(); clearPointerRepeat();
        chatPointerSelectionDragging = false; gameLogSelectionDragging = false;
        float dy = mobileScroll.deltaY();
        if (mobileScrollTarget == 1) {
            SettingsRowScroll rows = settingsRowScroll();
            if (rows == null) { mobileScroll.cancel(); return true; }
            float max = rows.rows().maximum();
            if (settingsSection() == GdxSettingsContract.Section.AUDIO)
                settingsAudioScroll = mobileOffset(settingsAudioScroll, dy, max);
            else if (settingsSection() == GdxSettingsContract.Section.APPEARANCE)
                settingsAppearanceScroll = mobileOffset(settingsAppearanceScroll, dy, max);
            else if (settingsSection() == GdxSettingsContract.Section.SHORTCUTS)
                shortcutScroll = mobileOffset(shortcutScroll, dy, max);
        } else if (mobileScrollTarget == 2) settingsDebugScroll = mobileOffset(
                settingsDebugScroll, -dy, mobileSettingsDebugMaximumScroll());
        else if (mobileScrollTarget == 3) quickChatScroll = mobileOffset(quickChatScroll, -dy, quickChatScrollMaximum);
        else if (mobileScrollTarget == 4) gameLogScroll = mobileOffset(gameLogScroll, -dy, gameLogMaximumScroll());
        else if (mobileScrollTarget == 5) {
            GdxNetworkBlockDialogLayout.Layout layout = GdxNetworkBlockDialogLayout.layout(
                    viewport.getWorldWidth(), viewport.getWorldHeight());
            networkBlocksScroll = mobileOffset(networkBlocksScroll, dy,
                    GdxNetworkBlockDialogLayout.maximumScroll(activeNetworkBlocks().size(), layout.rows()));
        }
        return true;
    }
    private boolean mobileEndContentScroll(int sx, int sy, int p) {
        if (mobileScroll == null || !mobileScroll.active()) return false;
        if (!mobileScroll.owns(p)) return true;
        if (!mobileContentScrollValid()) {
            mobileScroll.cancel(); clearPointerRepeat(); return true;
        }
        mobileMoveContentScroll(sx, sy, p);
        boolean tap = mobileScroll.active() && !mobileScroll.dragging();
        int target = mobileScrollTarget;
        mobileScroll.cancel(); clearPointerRepeat();
        chatPointerSelectionDragging = false; gameLogSelectionDragging = false;
        if (tap && (target == 1 || target == 3 || target == 5)) {
            pointer.set(sx, sy); viewport.unproject(pointer);
            handleUiClick(pointer.x, pointer.y);
        }
        return true;
    }
    private boolean mobileContentScrollValid() {
        return uiLayer == mobileScrollLayer && !isClientTransportReconnecting()
                && !blocksTableUtilities(activeDialog) && tableIdentityDialog == null
                && !tableGallery.isOpen() && !voiceNotesOpen && recoveryStopBarrier == null
                && !chatEditMenuOpen && !gameLogEditMenuOpen;
    }
    private static float mobileOffset(float value, float delta, float maximum) {
        return com.tonikelope.coronapoker.ios.TouchScrollGesture.offset(value, delta, maximum);
    }
    private float mobileSettingsDebugMaximumScroll() {
        return settingsDebugMaximumPixelScroll(settingsDebugVisualLines().size(),
                settingsDebugArea().viewport().height);
    }
    private void clearPointerRepeat() {
        if (pointerRepeat != null) pointerRepeat.clear();
        activePointerRepeat = null;
    }
