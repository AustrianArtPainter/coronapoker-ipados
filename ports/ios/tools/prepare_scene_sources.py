"""Install the iOS scene lifecycle; upstream rules and formatting remain intact."""
import re
from prepare_cinematics_sources import prepare_table


def _instance_methods(text, replace_method, transform):
    # Complete headers disambiguate overloads. Nested record/static helpers are
    # deliberately outside this migration: they remain the upstream oracle.
    headers = re.findall(r"^    (?:private|public|protected) (?!static\b)[^;{}]*?\([^;{}]*?\)\s*\{", text, re.M)
    for header in headers:
        text = replace_method(text, header, transform)
    return text


def prepare_scene(text, replace_method, port):
    allocation = "                batch = new SpriteBatch(2000);"
    if text.count(allocation) != 1:
        raise RuntimeError("Scene surface preparation ownership changed")
    text = text.replace(allocation, allocation + "\n                mobileClosingLayer.prepare(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());")
    boundary = "        pointerRepeatHits.clear();"
    if text.count(boundary) != 1:
        raise RuntimeError("Scene update/input boundary changed")
    text = text.replace(boundary, boundary + '''
        // Event and animation updates above always run, including on the
        // closing scene. Painting is a separate lifecycle below.
        mobileScene.synchronize(liveState, gameText.language());
        pointer.set(Gdx.input.getX(), Gdx.input.getY());
        viewport.unproject(pointer);
        IOSFrameEvidence.scene(finalSummary != null ? 2
                : preparationPhase == TableVisualEvent.PreparationStatus.Phase.READY ? 1 : 0,
                liveState.lastSequence());''')
    marker = "        drawTableScene();\n        mobileMeasureTableDraw();"
    if text.count(marker) != 1:
        raise RuntimeError("Scene painting boundary changed")
    text = text.replace(marker, '''        if (finalSummary == null) {
            IOSFrameEvidence.tableDraws++;
            drawTableScene();
            mobileMeasureTableDraw();
        }''')
    def full_frame(method):
        start = method.index("{") + 1
        end = method.rfind("}")
        return (method[:start] + "\n        long sceneFullAllocation = com.tonikelope.coronapoker.ios.IOSPerformance.beginAllocation();\n        try {"
                + method[start:end] + '\n        } finally { com.tonikelope.coronapoker.ios.IOSPerformance.endAllocation("alloc.fullTableFrame", sceneFullAllocation); }\n    }')
    text = replace_method(text, "    public void render()", full_frame)
    text = replace_method(text, "    private void drawTableScene()", lambda method:
        method.replace("        pointer.set(Gdx.input.getX(), Gdx.input.getY());\n        viewport.unproject(pointer);\n", ""))

    # Queries used by painting and hit testing read the same event projection.
    # The original livePlayerCount/visibleSeatPlayers and formatting algorithms
    # are retained verbatim; they are not rewritten as micro-optimizations.
    def migrate(method):
        header = method[:method.index("{")]
        if "livePlayerCount(" not in header:
            method = method.replace("livePlayerCount()", "mobileScenePlayerCount()")
        if "livePlayer(" not in header:
            method = method.replace("livePlayer(", "mobileScenePlayer(")
        method = method.replace("localHudLayout(", "mobileSceneHudLayout(")
        method = method.replace("localNamePlateBounds(", "mobileSceneNamePlate(")
        method = method.replace("localActionBadgeBounds(", "mobileSceneActionBadge(")
        method = method.replace("liveState.presentedHoleCards(", "mobileSceneHoleCards(")
        method = method.replace("liveState.showdownCardSelected(", "mobileSceneCardSelected(")
        if "lastActionLabelForSeat(" not in header:
            method = method.replace("lastActionLabelForSeat(", "mobileSceneSeatLabel(")
        return method
    text = _instance_methods(text, replace_method, migrate)
    text = replace_method(text, "    boolean isLiveReconnectingPlayer(", lambda method:
        method.replace("boolean isLiveReconnectingPlayer(", "private boolean mobileLegacyReconnectingPlayer(", 1))
    text = replace_method(text, "    private void drawCommunityHud(", lambda method:
        method.replace("        pointer.set(Gdx.input.getX(), Gdx.input.getY());\n        viewport.unproject(pointer);\n", ""))
    text = replace_method(text, "    private Texture liveCardFace(", '''    private Texture liveCardFace(String code) {
        if (code == null || code.isBlank()) return activeCardBack();
        return mobileFaces.face(liveDeck, code);
    }
    private Texture mobileLoadCardFace(String deck, String code) {
        String key = deck + "/hq/" + code;
        return liveCardFaces.computeIfAbsent(key,
                ignored -> deckCardTexture(deck, code + ".jpg"));
    }''')
    if text.count("liveCardFaces.clear();") != 3:
        raise RuntimeError("Card texture disposal ownership changed")
    text = text.replace("liveCardFaces.clear();", "liveCardFaces.clear();\n        mobileFaces.clear();")
    text = replace_method(text, "    static record LocalHudLayout(",
                          (port / "tools/mobile_scene_hud.java.inc").read_text())

    # Compile base seat geometry only when roster, size or seat ownership
    # changes; entrance animation modifies the pre-existing nodes every frame.
    text = replace_method(text, "    private void updateSeatPositions(", lambda method:
        method.replace("private void updateSeatPositions(", "private void mobileCompileSeatPositions(")
        .replace("                applyRemoteSeatEntryOffset(seats[i], width, height);", ""))
    text = replace_method(text, "    private void syncSeatsFromLiveState()", lambda method:
        method[:method.rfind("}")] + "        mobileScene.invalidateGeometry();\n    }")
    text = replace_method(text, "    void mobileRestore(", lambda method:
        method[:method.index("{") + 1] + "\n        mobileClosingLayer.invalidate();" + method[method.index("{") + 1:])
    text = replace_method(text, "    private void drawTableScene()", lambda method:
        method[:method.index("{") + 1] + "\n        if (finalSummary != null) IOSFrameEvidence.hiddenTableDraws++;" + method[method.index("{") + 1:])

    def closing(method):
        method = method.replace("private void drawFinalSummary()", "private void mobilePaintFinalSummary()")
        start = method.index("        Texture[] navIcons = {")
        end = method.index("        for (int index = 0; index < 5; index++)", start)
        method = method[:start] + "        Texture[] navIcons = mobileFinalScene.navIcons;\n        String[] navLabels = mobileFinalScene.navLabels;\n" + method[end:]
        method = method.replace("finalSummaryTitle(summary.reason(), gameText)", "mobileFinalScene.title")
        start = method.index("        String details = finalSummaryDate(")
        end = method.index("        drawFittedCentered(finalDetailFont", start)
        method = method[:start] + "        String details = mobileFinalScene.details;\n" + method[end:]
        method = method.replace("String hero = finalSummaryHero(summary.reason(), net, gameText);", "String hero = mobileFinalScene.hero;")
        start = method.index("            String result = balance.netResult()")
        end = method.index("            drawFittedCenteredInBox(finalCardBoldFont, result", start)
        method = method[:start] + "            String result = mobileFinalScene.results[index];\n" + method[end:]
        method = method.replace('uppercase(gameText.translate("balance.fichas")) + " "\n                            + formatAmount(balance.finalStack())', "mobileFinalScene.stacks[index]")
        method = method.replace('uppercase(gameText.translate("stats.buyin")) + " "\n                            + formatAmount(balance.totalBuyin())', "mobileFinalScene.buyins[index]")
        for value, field in [("0x11151bff", "nameColor"), ("0x707070ff", "neutralColor"), ("0x2c3138ff", "stackColor"), ("0x4e555eff", "buyinColor")]:
            method = method.replace("new Color(" + value + ")", "mobileFinalScene." + field)
        return method
    text = replace_method(text, "    private void drawFinalSummary()", closing)
    for signature in ["    public void dispose()", "    void disposeFailedCreation()"]:
        text = replace_method(text, signature, lambda method:
            method[:method.index("{") + 1] + "\n        mobileClosingLayer.dispose();" + method[method.index("{") + 1:])

    text = text[:text.rfind("}")] + (port / "tools/mobile_scene.java.inc").read_text() + "}\n"
    text = replace_method(text, "    private void drawSeats()", lambda method:
        method.replace("playerStackText(seat, livePlayer)","mobileStackText(seat, livePlayer)"))
    def seat_text(method):
        start=method.index("{")+1
        return method[:start]+'''
        // Preserve the original markup colour contract for custom names.
        if (text != null && (!font.getData().markupEnabled || text.indexOf('[')<0)) {
            mobileSeatText.draw(font,text,batch,x,y,width,height,color,alpha);
            return;
        }
'''+method[start:]
    text = replace_method(text, "    private void drawFittedCenteredInBox(", seat_text)
    for signature in ["    private void rebuildFonts(", "    public void dispose()", "    void disposeFailedCreation()"]:
        text = replace_method(text,signature,lambda method:
            method[:method.index("{")+1]+"\n        mobileSeatText.clear();"+method[method.index("{")+1:])
    text = text.replace("shufflePreparation.cancel(true);", "GifFramePreparation.cancel(shufflePreparation);")
    return prepare_table(text, replace_method, port)
