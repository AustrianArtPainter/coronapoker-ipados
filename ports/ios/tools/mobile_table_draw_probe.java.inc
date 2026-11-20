    private int mobileDrawProbeHand;
    private void mobileMeasureTableDraw() {
        if (!com.tonikelope.coronapoker.ios.TableDrawProbe.ENABLED) return;
        int hand = liveState.handNumber();
        TableSnapshot.PlayerSnapshot local = livePlayer(liveState.snapshot().localNickname());
        boolean stable = hand > mobileDrawProbeHand && local != null
                && local.holeCards().size() >= 2 && liveShuffle == null
                && liveCardFlights.isEmpty() && liveCommunityReveal == null
                && liveHoleReveal == null && liveHoleFold == null
                && liveCinematic == null && finalSummary == null
                && uiLayer == UI_NONE && activeDialog == null;
        if (!com.tonikelope.coronapoker.ios.TableDrawProbe.capture(stable)) return;
        mobileDrawProbeHand = hand;
        float width = viewport.getWorldWidth(), height = viewport.getWorldHeight();
        float tableCx = width / 2f, tableCy = height * 0.52f;
        float tableW = Math.min(1510f, width * 0.78f);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("noop", () -> {});
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("tableScene", this::drawTableScene);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("background", this::drawBackground);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("holeCardsBase", () -> drawHoleCards(false));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("holeCardsFront", () -> drawHoleCards(true));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("seats", this::drawSeats);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("seatShapesAndGlows", this::mobileProbeSeatShapes);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("seatAvatarsAndText", this::mobileProbeSeatSprites);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("latencyDots", this::drawLatencyDots);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("positionChips", this::drawPositionChips);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("seatActionLabel", () -> lastActionLabelForSeat(1));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("seatActionColor", () -> lastActionColorForSeat(1));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("tableAvatar", () -> tableAvatar(seats[1].name));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("lobbySnapshot", () -> tableChat.snapshot());
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("cardBack", this::activeCardBack);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("fixedCaption", () -> uppercase("WAITING FOR TURN", gameText));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("fixedGlyphLayout", () -> glyph.setText(stackFont, "123.4"));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("fixedFittedText", () -> {
            batch.begin(); drawFittedCenteredInBox(stackFont, "123.4", 0, 0, 160, 24, Color.WHITE, 1); batch.end();
        });
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("fixedRoundedRect", () -> {
            shapes.begin(ShapeRenderer.ShapeType.Filled); roundedRect(0, 0, 200, 120, 12); shapes.end();
        });
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("cardsAndPot", () -> drawCardsAndPot(tableCx, tableCy, tableW));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("localHud", () -> drawLocalHud(width, height));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("showdownHover", () -> updateLiveShowdownHover(width));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("callCostOverlay", () -> drawCallCostOverlay(tableCx, tableCy, tableW));
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("handOverlay", this::drawHandOverlay);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("showdownOverlay", this::drawShowdownOverlay);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("chatNotices", this::drawSeatChatNotices);
        com.tonikelope.coronapoker.ios.TableDrawProbe.measure("noopEnd", () -> {});
        com.tonikelope.coronapoker.ios.TableDrawProbe.save();
    }
