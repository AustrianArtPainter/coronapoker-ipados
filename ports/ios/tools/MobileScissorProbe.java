package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.nio.IntBuffer;
import sun.misc.Unsafe;

/** Captures the real frontend clip command in logical/back-buffer fixtures, without a GPU. */
public final class MobileScissorProbe {
    public static void main(String[] args) throws Exception {
        Field memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        Unsafe memory = (Unsafe) memoryField.get(null);
        GdxFrontendScreen screen = (GdxFrontendScreen) memory.allocateInstance(GdxFrontendScreen.class);
        FitViewport viewport = new FitViewport(1920,1080);
        Field viewField = GdxFrontendScreen.class.getDeclaredField("viewport"); viewField.setAccessible(true); viewField.set(screen,viewport);
        CoronaPokerGdxTable table=(CoronaPokerGdxTable)memory.allocateInstance(CoronaPokerGdxTable.class);
        ExtendViewport tableViewport=new ExtendViewport(1920,1080); tableViewport.setWorldSize(1920,1080);
        Field tableViewField=CoronaPokerGdxTable.class.getDeclaredField("viewport"); tableViewField.setAccessible(true); tableViewField.set(table,tableViewport);
        int[][] command = {null};
        int[][] glViewport = {null};
        Gdx.gl = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(),new Class<?>[]{GL20.class},
                (proxy,method,arguments) -> {
                    if(method.getName().equals("glScissor")) command[0]=Arrays.stream(arguments).mapToInt(value -> (Integer)value).toArray();
                    if(method.getName().equals("glGetIntegerv") && (Integer)arguments[0]==GL20.GL_VIEWPORT)
                        ((IntBuffer)arguments[1]).put(0,glViewport[0]);
                    return null;
                });
        Method clip = GdxFrontendScreen.class.getDeclaredMethod("enableWorldScissor",Rectangle.class); clip.setAccessible(true);
        Method tableClip=CoronaPokerGdxTable.class.getDeclaredMethod("enableTableScissor",Rectangle.class); tableClip.setAccessible(true);
        Rectangle bounds = new Rectangle(523,324,810,420);
        int failures=0;
        // Last column: 1 captures the scene into the half-resolution modal FBO.
        int[][] fixtures = {{1,0,76,1210,681,0},{2,0,76,1210,681,0},{3,0,76,1210,681,0},
                {2,221,356,768,432,0},{2,0,76,1210,681,1},{2,221,356,768,432,1}};
        for(int[] fixture:fixtures) {
            int scale=fixture[0];
            Gdx.graphics=(Graphics)Proxy.newProxyInstance(Graphics.class.getClassLoader(),new Class<?>[]{Graphics.class},
                    (proxy,method,arguments) -> switch(method.getName()) {
                        case "getWidth" -> 1210; case "getHeight" -> 834;
                        case "getBackBufferWidth" -> 1210*scale; case "getBackBufferHeight" -> 834*scale;
                        default -> null;
                    });
            viewport.setScreenBounds(fixture[1],fixture[2],fixture[3],fixture[4]);
            tableViewport.setScreenBounds(fixture[1],fixture[2],fixture[3],fixture[4]);
            glViewport[0]=fixture[5]==1?new int[]{0,0,1210*scale/2,834*scale/2}
                    :new int[]{fixture[1]*scale,fixture[2]*scale,fixture[3]*scale,fixture[4]*scale};
            float ratioX=(float)glViewport[0][2]/fixture[3];
            float ratioY=(float)glViewport[0][3]/fixture[4];
            int[] expected={
                glViewport[0][0]+Math.round((Math.round(fixture[1]+bounds.x*fixture[3]/1920f)-fixture[1])*ratioX),
                glViewport[0][1]+Math.round((Math.round(fixture[2]+bounds.y*fixture[4]/1080f)-fixture[2])*ratioY),
                Math.max(1,Math.round(Math.max(1,Math.round(bounds.width*fixture[3]/1920f))*ratioX)),
                Math.max(1,Math.round(Math.max(1,Math.round(bounds.height*fixture[4]/1080f))*ratioY))};
            for(int target=0;target<2;target++) {
                (target==0?clip:tableClip).invoke(target==0?screen:table,bounds);
                boolean passed=Arrays.equals(command[0],expected);
                System.out.println("target="+(target==0?"frontend":"table")+" scale="+scale+" viewport="+Arrays.toString(fixture)+" actual="+Arrays.toString(command[0])
                        +" expected="+Arrays.toString(expected)+" passed="+passed);
                if(!passed)failures++;
            }
        }
        if(failures>0)throw new AssertionError(failures+" clip-coordinate fixtures failed");
        System.out.println("PASS 12 real frontend/table clip commands: 1x, 2x, 3x, keyboard-shifted viewport and modal FBO capture");
    }
}
