package com.tonikelope.coronapoker.ios;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.viewport.Viewport;
public final class IOSKeyboard {
    private static boolean visible;
    // fit() and its viewport are owned by the UIKit render thread. Keep the
    // query live while reusing only its four-value transfer buffer.
    private static final float[] SAFE_AREA_INSETS = new float[4];
    public static void visible(boolean requested) {
        if (requested == visible || Gdx.input == null) return;
        visible = requested;
        Gdx.input.setOnscreenKeyboardVisible(requested);
    }
    public static void fit(Viewport viewport) {
        int previousStage=com.tonikelope.coronapoker.gdx.IOSFrameEvidence.ATTRIBUTION_ENABLED
                ? IOSFrameAttribution.currentStage() : -1;
        if (previousStage>=0) IOSFrameAttribution.stage(IOSFrameAttribution.KEYBOARD_FIT);
        try {
        int width=Gdx.graphics.getWidth(),height=Gdx.graphics.getHeight();
        float[] safe=SAFE_AREA_INSETS;
        IOSPlatform.readSafeAreaInsets(safe);
        int left=Math.round(width*safe[0]),top=Math.round(height*safe[1]);
        int right=Math.round(width*safe[2]),bottom=Math.round(height*safe[3]);
        int overlap=Math.round(height*Math.max(0,Math.min(0.75f,IOSPlatform.keyboardOcclusion())));
        bottom=Math.max(bottom,overlap);
        viewport.update(Math.max(1,width-left-right),Math.max(1,height-top-bottom),true);
        viewport.setScreenX(viewport.getScreenX()+left);
        viewport.setScreenY(viewport.getScreenY()+bottom);
        } finally {
            if (previousStage>=0) IOSFrameAttribution.stage(previousStage);
        }
    }
    private IOSKeyboard() {}
}
