"""Insert opt-in, exclusive render stages after the mobile source transforms.

The caller supplies its reviewed method parser. Every boundary must occur once;
this converter changes no original statements and refuses a second application.
"""

HELPER = "com.tonikelope.coronapoker.ios.IOSFrameAttribution"


def marker(stage, indent="        "):
    return (indent + "if (IOSFrameEvidence.ATTRIBUTION_ENABLED)\n"
            + indent + "    " + HELPER + ".stage(" + HELPER + "." + stage + ");")


def change(method, before, after):
    if method.count(before) != 1:
        raise RuntimeError("Frame attribution boundary changed: " + before)
    return method.replace(before, after)


def fresh(text):
    if HELPER + ".stage(" in text:
        raise RuntimeError("Frame attribution stages were already installed")


def ordered(method, anchors):
    positions = []
    for anchor in anchors:
        if method.count(anchor) != 1:
            raise RuntimeError("Frame attribution boundary changed: " + anchor)
        positions.append(method.index(anchor))
    if positions != sorted(positions):
        raise RuntimeError("Frame attribution boundary order changed")


def wrap(method, entry, exit_stage):
    opening = method.index("{")
    return (method[:opening + 1] + "\n        try {\n" + marker(entry)
            + method[opening + 1:-1] + "\n        } finally {\n"
            + marker(exit_stage, "            ") + "\n        }\n    }")


def prepare_frontend(text, replace_method):
    fresh(text)

    def render(method):
        font = "        refreshFontQualityIfDue();"
        sync = "        syncMusicForSurface();"
        felt = "        drawFeltBackground();"
        text_layer = "        shapes.end();\n\n        batch.begin();"
        tail = "        batch.end();\n\n        if (shieldBackgroundPointer) pointer.set(livePointerX, livePointerY);"
        keyboard = ("        com.tonikelope.coronapoker.ios.IOSKeyboard.visible(activeField != null);\n"
                    "        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();")
        ordered(method, (keyboard, font, sync, felt, text_layer, tail))
        method = change(method, font, marker("FONT") + "\n" + font + "\n" + marker("FRONT_UPDATE"))
        method = change(method, sync, sync + "\n" + marker("FRONT_PREPARE"))
        method = change(method, felt, marker("FRONT_SHAPES") + "\n" + felt)
        method = change(method, text_layer, "        shapes.end();\n\n" + marker("FRONT_TEXT")
                        + "\n        batch.begin();")
        method = change(method, tail, "        batch.end();\n" + marker("FRONT_TAIL")
                        + "\n\n        if (shieldBackgroundPointer) pointer.set(livePointerX, livePointerY);")
        return wrap(method, "FRONT_KEYBOARD", "SHELL_TAIL")

    return replace_method(text, "    public void render()", render)


def prepare_table(text, replace_method):
    fresh(text)

    def render(method):
        font = "        refreshFontQualityIfDue();"
        pointer = ("        mobileScene.synchronize(liveState, gameText.language());\n"
                   "        pointer.set(Gdx.input.getX(), Gdx.input.getY());")
        background = "        drawBackground();"
        tail = "        drawUiLayer();"
        prelude = "        if (mobileResumeRequired) GdxTableRenderer.resumeAll();\n        mobilePreparePendingShuffle();"
        ordered(method, (prelude, font, pointer, background, tail))
        method = change(method, font, marker("FONT") + "\n" + font + "\n" + marker("TABLE_UPDATE"))
        method = change(method, pointer,
                        "        mobileScene.synchronize(liveState, gameText.language());\n"
                        + marker("TABLE_PREPARE") + "\n        pointer.set(Gdx.input.getX(), Gdx.input.getY());")
        method = change(method, background, marker("TABLE_PAINT") + "\n" + background)
        method = change(method, tail, marker("TABLE_TAIL") + "\n" + tail)
        return wrap(method, "TABLE_PRELUDE", "SHELL_TAIL")

    def summary(method):
        guard = "        if (finalSummary == null) return;"
        live = "            mobilePaintFinalSummary();\n            return;"
        compile_layer = "        if (!mobileClosingLayer.valid()) {\n            mobileClosingLayer.begin();"
        replay = "        batch.setShader(null);"
        ordered(method, (guard, live, compile_layer, replay))
        method = change(method, guard, guard + "\n        try {\n" + marker("SUMMARY_PREPARE"))
        method = change(method, live, marker("SUMMARY_PAINT", "            ") + "\n" + live)
        method = change(method, compile_layer, "        if (!mobileClosingLayer.valid()) {\n"
                        + marker("SUMMARY_PAINT", "            ") + "\n            mobileClosingLayer.begin();")
        method = change(method, replay, marker("SUMMARY_REPLAY") + "\n" + replay)
        return (method[:-1] + "\n        } finally {\n" + marker("TABLE_PAINT", "            ")
                + "\n        }\n    }")

    text = replace_method(text, "    public void render()", render)
    return replace_method(text, "    private void drawFinalSummary()", summary)
