package com.tonikelope.coronapoker.ios;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.viewport.Viewport;
public final class IOSKeyboard {
    private static boolean visible;
    public static void visible(boolean requested) {
        if (requested == visible || Gdx.input == null) return;
        visible = requested;
        Gdx.input.setOnscreenKeyboardVisible(requested);
    }
    public static void fit(Viewport viewport) {
        int width=Gdx.graphics.getWidth(),height=Gdx.graphics.getHeight();
        float[] safe=IOSPlatform.safeAreaInsets();
        int left=Math.round(width*safe[0]),top=Math.round(height*safe[1]);
        int right=Math.round(width*safe[2]),bottom=Math.round(height*safe[3]);
        int overlap=Math.round(height*Math.max(0,Math.min(0.75f,IOSPlatform.keyboardOcclusion())));
        bottom=Math.max(bottom,overlap);
        viewport.update(Math.max(1,width-left-right),Math.max(1,height-top-bottom),true);
        viewport.setScreenX(viewport.getScreenX()+left);
        viewport.setScreenY(viewport.getScreenY()+bottom);
    }
    private IOSKeyboard() {}
}
