package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.badlogic.gdx.graphics.g2d.*;

/** Bounded plain-text layouts. Stable hits refill existing vertex storage, without allocation. */
final class IOSFittedTextCache {
    private static final int CAPACITY = 128;
    private final Entry[] entries = new Entry[CAPACITY];
    private long clock;
    static long builds, hits;
    void draw(BitmapFont font, String text, Batch batch, float x, float y, float width, float height,
              Color color, float alpha, Viewport viewport) {
        BitmapFont.BitmapFontData data=font.getData();
        float sx=data.scaleX, sy=data.scaleY;
        Entry result=null; int victim=0; long oldest=Long.MAX_VALUE;
        for (int i=0;i<CAPACITY;i++) {
            Entry e=entries[i];
            if (e==null) { victim=i; break; }
            if (e.font==font && e.width==width && e.height==height && e.sx==sx && e.sy==sy && e.text.equals(text)) {
                result=e; hits++; break;
            }
            if(e.used<oldest) { oldest=e.used; victim=i; }
        }
        if(result==null) {
            result=new Entry(font,text,width,height,sx,sy); entries[victim]=result; builds++;
        }
        result.used=++clock;
        float left = GdxPixelText.snapX(x + (width - result.glyphWidth) / 2f, viewport);
        float baseline = GdxPixelText.snapY(y + (height + result.glyphHeight) / 2f, viewport);
        result.cache.setText(result.layout, left, baseline);
        result.tint.set(color.r,color.g,color.b,alpha);
        result.cache.tint(result.tint);
        result.cache.draw(batch);
        font.setColor(Color.WHITE);
    }
    void clear() { java.util.Arrays.fill(entries,null); }
    private static final class Entry {
        final BitmapFont font;
        final String text;
        final float width,height,sx,sy,glyphWidth,glyphHeight;
        final BitmapFontCache cache;
        final GlyphLayout layout=new GlyphLayout();
        final Color tint=new Color();
        long used;
        Entry(BitmapFont font,String text,float width,float height,float sx,float sy) {
            this.font=font;this.text=text;this.width=width;this.height=height;this.sx=sx;this.sy=sy;
            Color original=new Color(font.getColor());
            try {
                font.setColor(Color.WHITE); layout.setText(font,text);
                glyphWidth=layout.width; glyphHeight=layout.height;
                cache=new BitmapFontCache(font,false);
                cache.setText(layout,0,0);
            } finally { font.setColor(original); }
        }
    }
}
