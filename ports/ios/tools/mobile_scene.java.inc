
    private final IOSSceneProjection mobileScene = new IOSSceneProjection();
    private final IOSSceneAssets<Texture> mobileFaces = new IOSSceneAssets<>(
            this::availableDeck, this::mobileLoadCardFace);
    private final float[] mobileSeatBase = new float[SEAT_COUNT * 8];
    private long mobileLayoutRevision = Long.MIN_VALUE;
    private float mobileLayoutWidth = Float.NaN, mobileLayoutHeight = Float.NaN;
    private Texture mobileLayoutCardBack;
    private LocalHudLayout mobileHud;
    private float mobileHudWidth = Float.NaN;
    private final Rectangle mobileNamePlate = new Rectangle(), mobileActionBadge = new Rectangle();
    private final IOSOpaqueSceneLayer mobileClosingLayer = new IOSOpaqueSceneLayer();
    private final IOSFittedTextCache mobileSeatText = new IOSFittedTextCache();
    private MobileFinalScene mobileFinalScene;
    private int mobileFinalPage = -1, mobileFinalPointer = -2;
    private boolean mobileFinalSound, mobileFinalOutput;
    private Texture mobileFinalFelt;
    private float mobileFinalWidth = Float.NaN, mobileFinalHeight = Float.NaN;
    private GdxAdaptiveFontQuality.Profile mobileFinalFont;
    private com.tonikelope.coronapoker.core.LobbySnapshot mobileNetworkSource;
    private long mobileNetworkRoster = Long.MIN_VALUE;
    private final boolean[] mobileNetworkReconnecting = new boolean[SEAT_COUNT];

    private TableSnapshot.PlayerSnapshot mobileScenePlayer(Seat seat) {
        if (liveState == null || seat == null || seat.name.isBlank()) return null;
        mobileScene.synchronize(liveState, gameText.language());
        TableSnapshot.PlayerSnapshot result = mobileScene.readout(seat.index).player;
        return result != null && result.nickname().equals(seat.name)
                ? result : mobileScenePlayer(seat.name);
    }
    private TableSnapshot.PlayerSnapshot mobileScenePlayer(String nickname) {
        if (liveState == null || nickname == null || nickname.isBlank()) return null;
        mobileScene.synchronize(liveState, gameText.language());
        TableSnapshot.PlayerSnapshot result = mobileScene.player(nickname);
        return result == null ? livePlayer(nickname) : result;
    }
    boolean isLiveReconnectingPlayer(String nickname) {
        if (tableChat == null || nickname == null || nickname.isBlank()) return false;
        mobileScene.synchronize(liveState, gameText.language());
        com.tonikelope.coronapoker.core.LobbySnapshot next = tableChat.snapshot();
        if (mobileNetworkSource != next || mobileNetworkRoster != mobileScene.geometryRevision()) {
            mobileNetworkSource = next;
            mobileNetworkRoster = mobileScene.geometryRevision();
            for (int i = 0; i < seats.length; i++)
                mobileNetworkReconnecting[i] = mobileLegacyReconnectingPlayer(seats[i].name);
        }
        TableSnapshot.PlayerSnapshot player = mobileScenePlayer(nickname);
        if (player != null && player.exited()) return false;
        for (int i = 0; i < seats.length; i++) if (seats[i].name.equals(nickname))
            return mobileNetworkReconnecting[i];
        return mobileLegacyReconnectingPlayer(nickname);
    }

    private int mobileScenePlayerCount() {
        mobileScene.synchronize(liveState, gameText.language());
        return mobileScene.count();
    }
    private List<TableSnapshot.CardSnapshot> mobileSceneHoleCards(String nickname) {
        mobileScene.synchronize(liveState, gameText.language());
        List<TableSnapshot.CardSnapshot> result = mobileScene.holeCards(nickname);
        return result == null ? liveState.presentedHoleCards(nickname) : result;
    }
    private Boolean mobileSceneCardSelected(String nickname, int slot, boolean community) {
        mobileScene.synchronize(liveState, gameText.language());
        if (!community && mobileScene.holeCards(nickname) == null)
            return liveState.showdownCardSelected(nickname, slot, false);
        return mobileScene.selected(nickname, slot, community);
    }

    private LocalHudLayout mobileSceneHudLayout(float width) {
        if (mobileHud == null || mobileHudWidth != width) {
            mobileHud = localHudLayout(width);
            mobileHudWidth = width;
        }
        return mobileHud;
    }

    private Rectangle mobileSceneNamePlate(Seat local, float width) {
        float x = MathUtils.clamp(seatAvatarX(local) + AVATAR_OUTER_RADIUS + 10f,
                8f, Math.max(8f, width - LOCAL_NAME_PLATE_WIDTH - 8f));
        return mobileNamePlate.set(x, local.y - LOCAL_NAME_PLATE_HEIGHT / 2f
                + LOCAL_NAME_PLATE_VERTICAL_OFFSET, LOCAL_NAME_PLATE_WIDTH, LOCAL_NAME_PLATE_HEIGHT);
    }
    private Rectangle mobileSceneActionBadge(Seat local, float width) {
        Rectangle name = mobileSceneNamePlate(local, width);
        return mobileActionBadge.set(name.x, name.y - LOCAL_ACTION_BADGE_HEIGHT - 4f,
                name.width, LOCAL_ACTION_BADGE_HEIGHT);
    }

    private void updateSeatPositions(float width, float height) {
        mobileScene.synchronize(liveState, gameText.language());
        if (mobileLayoutRevision != mobileScene.geometryRevision()
                || mobileLayoutWidth != width || mobileLayoutHeight != height
                || mobileLayoutCardBack != activeCardBack()) {
            mobileCompileSeatPositions(width, height);
            for (int i = 0; i < seats.length; i++) {
                Seat seat = seats[i]; int offset = i * 8;
                mobileSeatBase[offset] = seat.x;
                mobileSeatBase[offset + 1] = seat.y;
                mobileSeatBase[offset + 2] = seat.podX;
                mobileSeatBase[offset + 3] = seat.podY;
                mobileSeatBase[offset + 4] = seat.stackX;
                mobileSeatBase[offset + 5] = seat.stackY;
                mobileSeatBase[offset + 6] = seat.positionX;
                mobileSeatBase[offset + 7] = seat.positionY;
            }
            mobileLayoutRevision = mobileScene.geometryRevision();
            mobileLayoutWidth = width; mobileLayoutHeight = height;
            mobileLayoutCardBack = activeCardBack();
            IOSFrameEvidence.layoutBuilds++;
        }
        for (int i = 0; i < seats.length; i++) {
            Seat seat = seats[i]; int offset = i * 8;
            seat.x = mobileSeatBase[offset]; seat.y = mobileSeatBase[offset + 1];
            seat.podX = mobileSeatBase[offset + 2]; seat.podY = mobileSeatBase[offset + 3];
            seat.stackX = mobileSeatBase[offset + 4]; seat.stackY = mobileSeatBase[offset + 5];
            seat.positionX = mobileSeatBase[offset + 6]; seat.positionY = mobileSeatBase[offset + 7];
            if (i > 0) applyRemoteSeatEntryOffset(seat, width, height);
        }
        int dealerIndex = dealerSeat();
        Seat dealer = dealerIndex < 0 ? null : seats[dealerIndex];
        dealerSourceX = dealer == null ? width / 2f : dealer.x;
        dealerSourceY = dealer == null ? height / 2f : dealer.y;
    }

    private String mobileSceneSeatLabel(int index) {
        mobileScene.synchronize(liveState, gameText.language());
        Seat seat = seats[index];
        TableSnapshot.PlayerSnapshot player = mobileScenePlayer(seat);
        int kind=0; long number=0; String first="", second="";
        TableVisualEvent.RebuyDecision.Phase rebuy=liveState.rebuyDecision(seat.name);
        if(player==null) kind=0;
        else if(player.timedOut()) kind=1;
        else if(rebuy!=null) {
            kind=20+rebuy.ordinal(); number=remoteRebuyCinematicActive()?1:0;
            if(rebuy==TableVisualEvent.RebuyDecision.Phase.WAITING) {
                float started=remoteRebuyStartedAt.getOrDefault(seat.name,totalTime);
                number+=2L*Math.max(0,(int)Math.ceil(GameTiming.REBUY_DIALOG_COUNTDOWN_SECONDS
                        -Math.max(0f,totalTime-started)));
            }
        } else if(isLiveReconnectingPlayer(seat.name)) kind=3;
        else if(isLiveThinkingSeat(seat)) kind=4;
        else if(player.spectator()) { kind=5; number=(index==0?2:0)+(player.warming()?1:0); }
        else if(liveState.isIwtsthCandidate(seat.name)) { kind=6; number=iwtsthBlinkOn()?1:0; }
        else if(liveState.rabbitNoticeActive(seat.name)) kind=7;
        else {
            if(liveState.hasLateShownHand(seat.name)) {
                first=liveState.resolvedHandName(seat.name);
                if(first.isBlank()) first=player.handName();
                if(!first.isBlank()) kind=8;
            }
            if(kind!=8) {
                if(liveState.foldedThisHand(seat.name)) { kind=9; first=liveState.actionLabel(seat.name); }
                else if(liveState.hasHandResult(seat.name)) { kind=10; first=liveState.resolvedHandName(seat.name); }
                else if(Boolean.TRUE.equals(liveState.resolvedHandWinner(seat.name))) { kind=11; number=index==0?1:0; }
                else if(liveHandLabelVisible(player)) {
                    kind=12; first=player.handName();
                    Float percentage=liveState.partialHandPercentage(seat.name);
                    if(percentage==null) number=Long.MIN_VALUE;
                    else if(percentage<0f) number=Long.MIN_VALUE+1;
                    else {
                        LiveHandProbability probability=liveHandProbabilities.get(seat.name);
                        float shown=probability==null?percentage:probability.valueAt(System.nanoTime());
                        number=Float.floatToIntBits(shown);
                    }
                } else if(!historicalActionLabelVisible(liveState.hasRevealedHoleCards(seat.name))) kind=13;
                else {
                    kind=14; first=liveState.actionLabel(seat.name);
                    second=first.isBlank()?player.lastAction():"";
                    TableVisualEvent.PlayerAction.ActionKind action=liveState.actionKind(seat.name);
                    number=action==null?-1:action.ordinal();
                }
            }
        }
        IOSSeatLabelKey key=mobileScene.readout(index).labelKey;
        String language=gameText.language();
        if(!key.matches(kind,language,first,second,number)) {
            key.update(kind,language,first,second,number,lastActionLabelForSeat(index));
            IOSFrameEvidence.labelBuilds++;
        }
        return key.text();
    }

    private String mobileStackText(Seat seat, TableSnapshot.PlayerSnapshot player) {
        boolean buyin=player!=null && totalTime<seat.buyInVisibleUntil;
        int pending=liveState.immediateRebuyAmount(seat.name);
        if(!buyin && pending<=0) return seat.stackText;
        IOSSeatLabelKey key=mobileScene.readout(seat.index).stackKey;
        long number=buyin?Double.doubleToLongBits(player.buyIn()):pending;
        String displayed=buyin?"":seat.stackText;
        if(!key.matches(buyin?1:2,"",displayed,"",number))
            key.update(buyin?1:2,"",displayed,"",number,playerStackText(seat,player));
        return key.text();
    }

    private void drawFinalSummary() {
        if (finalSummary == null) return;
        IOSFrameEvidence.summaryDraws++;
        if (mobileFinalScene == null || mobileFinalScene.summary != finalSummary
                || !mobileFinalScene.language.equals(gameText.language())) {
            mobileFinalScene = new MobileFinalScene(finalSummary);
            mobileClosingLayer.invalidate();
        }
        float width = viewport.getWorldWidth(), height = viewport.getWorldHeight();
        float elapsed = totalTime - finalSummaryOpenedAt;
        float settledAt = Math.max(FINAL_SUMMARY_REVEAL_SECONDS,
                Math.max(FINAL_AMOUNT_ROLL_SECONDS + FINAL_AMOUNT_BLINK_STEP_SECONDS * FINAL_AMOUNT_BLINK_STEPS,
                FINAL_CARD_DEAL_START_SECONDS + Math.max(0, finalSummaryVisibleCards(width) - 1)
                        * FINAL_CARD_DEAL_STAGGER_SECONDS + FINAL_CARD_DEAL_SECONDS)) + .05f;
        // A modal owns its existing downsampled FBO. Never nest a full-screen
        // retained surface inside it or alter the modal's blur/alpha contract.
        if (elapsed < settledAt || modalBackdropCaptureActive) {
            mobilePaintFinalSummary();
            return;
        }
        int target = finalSummaryPointerTargetAt(width, height, pointer.x, pointer.y,
                finalSummaryPage, finalSummary.balances().size());
        boolean sound = audioControl.enabled(), output = audioOutputAvailable();
        if (mobileFinalPage != finalSummaryPage || mobileFinalPointer != target
                || mobileFinalSound != sound || mobileFinalOutput != output
                || mobileFinalFelt != feltTexture || mobileFinalFont != fontQualityProfile
                || mobileFinalWidth != width || mobileFinalHeight != height) {
            mobileClosingLayer.invalidate();
        }
        for (int i = 0; i < mobileFinalScene.avatars.length; i++) {
            Texture avatar = tableAvatar(finalSummary.balances().get(i).nickname());
            if (mobileFinalScene.avatars[i] != avatar) {
                mobileFinalScene.avatars[i] = avatar; mobileClosingLayer.invalidate();
            }
        }
        mobileClosingLayer.prepare(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        if (!mobileClosingLayer.valid()) {
            mobileClosingLayer.begin();
            try { viewport.apply(); mobilePaintFinalSummary(); }
            finally { mobileClosingLayer.end(); viewport.apply(); }
            mobileFinalPage = finalSummaryPage; mobileFinalPointer = target;
            mobileFinalSound = sound; mobileFinalOutput = output;
            mobileFinalFelt = feltTexture; mobileFinalFont = fontQualityProfile;
            mobileFinalWidth = width; mobileFinalHeight = height;
            IOSFrameEvidence.summaryCompiles++;
        }
        batch.setShader(null);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.begin(); batch.setColor(Color.WHITE);
        IOSOpaqueSceneLayer.replay(batch, mobileClosingLayer.texture(), width, height);
        batch.end();
        IOSFrameEvidence.summaryReplays++;
    }

    private final class MobileFinalScene {
        final TableSessionSummary summary;
        final String language, title, details, hero;
        final String[] navLabels, results, stacks, buyins;
        final Texture[] navIcons, avatars;
        final Color nameColor = new Color(0x11151bff), neutralColor = new Color(0x707070ff),
                stackColor = new Color(0x2c3138ff), buyinColor = new Color(0x4e555eff);
        MobileFinalScene(TableSessionSummary summary) {
            this.summary = summary; language = gameText.language();
            title = finalSummaryTitle(summary.reason(), gameText);
            TableSessionSummary.PlayerBalance local = summary.localBalance();
            hero = finalSummaryHero(summary.reason(), local == null ? 0d : local.netResult(), gameText);
            details = finalSummaryDate(summary.endedAtMillis()) + "   ("
                    + finalSummaryDuration(summary.durationSeconds()) + ")   [" + summary.handCount() + " "
                    + gameText.translate(summary.handCount() == 1 ? "gdx.final.hand" : "gdx.final.hands") + "]";
            navIcons = new Texture[]{finalMenuIcon, finalLogIcon, finalStatsIcon, finalContinueIcon, finalScreenshotIcon};
            navLabels = new String[]{uppercase(gameText.translate("ui.menu_principal")),
                    uppercase(gameText.translate("log.registro_de_la_timba")),
                    uppercase(gameText.translate("stats.estadisticas")),
                    uppercase(gameText.translate(tableHost ? "game.continuar_esta_timba" : "gdx.final.reconnect_server")),
                    uppercase(gameText.translate("menu.visor_capturas"))};
            int count = summary.balances().size();
            results = new String[count]; stacks = new String[count]; buyins = new String[count]; avatars = new Texture[count];
            for (int i = 0; i < count; i++) {
                TableSessionSummary.PlayerBalance balance = summary.balances().get(i);
                results[i] = balance.netResult() > 0d ? uppercase(gameText.translate("ui.gana_4")) + " " + formatAmount(balance.netResult())
                        : balance.netResult() < 0d ? uppercase(gameText.translate("ui.pierde_2")) + " " + formatAmount(-balance.netResult())
                        : uppercase(gameText.translate("ui.ni_gana_ni_pierde"));
                stacks[i] = uppercase(gameText.translate("balance.fichas")) + " " + formatAmount(balance.finalStack());
                buyins[i] = uppercase(gameText.translate("stats.buyin")) + " " + formatAmount(balance.totalBuyin());
            }
        }
    }
