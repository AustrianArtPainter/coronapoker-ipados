package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.tonikelope.coronapoker.ios.*;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import sun.misc.Unsafe;

/** Real Pixmap release/GPU-only ownership, bounded cancellation and exact seat glyph vertices. */
public final class FinishingPreloadProbe {
    private static int textureId, uploadedHash;
    private static volatile Object sink;
    private static final ArrayList<Integer> hashes=new ArrayList<>();
    public static void main(String[] args) throws Exception {
        GdxNativesLoader.load();
        Gdx.gl=Gdx.gl20=(GL20)Proxy.newProxyInstance(GL20.class.getClassLoader(),new Class<?>[]{GL20.class},(p,m,a)-> {
            if(m.getName().equals("glGenTexture")) return ++textureId;
            if(m.getName().equals("glTexImage2D")) {
                ByteBuffer pixels=(ByteBuffer)a[8]; int hash=1;
                for(int i=pixels.position();i<pixels.limit();i++)hash=31*hash+pixels.get(i);
                uploadedHash=hash;
            }
            return defaultValue(m.getReturnType());
        });
        Gdx.graphics=(Graphics)Proxy.newProxyInstance(Graphics.class.getClassLoader(),new Class<?>[]{Graphics.class},(p,m,a)->defaultValue(m.getReturnType()));
        Field frames=PreloadedShuffleAnimation.class.getDeclaredField("frames");frames.setAccessible(true);
        Field uploaded=PreloadedShuffleAnimation.class.getDeclaredField("uploaded");uploaded.setAccessible(true);
        Path resources=Path.of(args[0]); int count=0;
        List<Path> clips=new ArrayList<>();
        for(String name:IOSCinematicAssets.OFFICIAL)clips.add(resources.resolve("cinematics/allin/"+name));
        try(var files=Files.walk(resources.resolve("images/decks"))) {
            files.filter(p->p.toString().endsWith("/gif/shuffle.gif")).sorted().forEach(clips::add);
        }
        for(Path file:clips) {
            byte[] data=Files.readAllBytes(file);
            int width=file.toString().contains("/allin/")?563:960;
            hashes.clear();
            PortableGif.decode(data,width,p->hashes.add(Arrays.hashCode(p.rgba())),()->false);
            var result=GifFramePreparation.start(()->data,width,256L*1024*1024);
            var cached=result.get(10,TimeUnit.SECONDS);
            long timeout=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            int index=0;
            while(!cached.uploadNext()) {
                int n=uploaded.getInt(cached);
                if(n>index) { require(uploadedHash==hashes.get(index),"Pooled pixels changed");index=n; }
                if(System.nanoTime()>timeout)throw new AssertionError("Preparation did not converge");
                Thread.sleep(1);
            }
            if(uploaded.getInt(cached)>index)require(uploadedHash==hashes.get(index),"Final pixels changed");
            TextureRegion[] regions=(TextureRegion[])frames.get(cached);
            require(regions.length==hashes.size(),"Frame count changed");
            PortableGif.Info info=PortableGif.inspect(data);long time=0;
            for(int i=0;i<regions.length;i++) {
                require(cached.frameAt((time+1)/1000f,false)==regions[i],"Prepared frame timing changed");
                time+=info.frames().get(i).delayMillis();
            }
            require(cached.durationMillis()==time,"Prepared clip duration changed");
            for(TextureRegion region:regions) {
                TextureData td=region.getTexture().getTextureData();
                Field cpu=td.getClass().getDeclaredField("image");cpu.setAccessible(true);
                require(cpu.get(td)==null && !td.isManaged(),"Texture retains CPU pixels");
            }
            require(GifFramePreparation.LIVE_BYTES.get()==0,"Pixel pool remains after upload");
            require(IOSFrameEvidence.preloadPixmapCreates==IOSFrameEvidence.preloadPixmapDisposals,"Pixmap not disposed");
            var borrowed=StreamingGifTextureAnimation.borrowed(cached);borrowed.dispose();
            require(cached.frameAt(0)!=null,"Borrowed playback disposed owned GPU frames");
            count+=regions.length;cached.dispose();cached.dispose();
        }
        byte[] data=Files.readAllBytes(clips.get(0));
        var cancel=GifFramePreparation.start(()->data,563,256L*1024*1024);
        cancel.get(10,TimeUnit.SECONDS);Thread.sleep(30);GifFramePreparation.cancel(cancel);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(GifFramePreparation.LIVE_BYTES.get()!=0 && System.nanoTime()<until)Thread.sleep(10);
        require(GifFramePreparation.LIVE_BYTES.get()==0,"Cancelled full producer kept pixel pool/worker");
        var oversized=GifFramePreparation.start(()->data,563,1);
        try { oversized.get(10,TimeUnit.SECONDS);throw new AssertionError("Budget ignored"); }
        catch(ExecutionException expected) { require(expected.getCause() instanceof GifFramePreparation.BudgetExceeded,"Wrong budget failure"); }
        textVerticesAndAllocations();
        SoundStartGate gate=new SoundStartGate();
        require(gate.allow(1) && !gate.allow(39_999_999) && gate.allow(40_000_001),"40ms boundary changed");
        require(!gate.allow(50_000_001) && gate.allow(80_000_001),"Suppressed cue extended debounce");
        System.out.println("PASS "+clips.size()+" actual clips / "+count+" frames: exact pooled RGBA, released Pixmaps, GPU-only textures, bounded cancellation, seat glyphs/steady allocations, 40ms gate");
    }

    private static void textVerticesAndAllocations() throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe unsafe=(Unsafe)f.get(null);
        CaptureBatch batch=(CaptureBatch)unsafe.allocateInstance(CaptureBatch.class);batch.vertices=new float[4096];
        Pixmap pixel=new Pixmap(8,8,Pixmap.Format.RGBA8888);Texture texture=new Texture(pixel);pixel.dispose();
        BitmapFont.BitmapFontData data=new BitmapFont.BitmapFontData();
        data.lineHeight=16;data.capHeight=12;data.ascent=0;data.descent=-3;data.down=-16;data.spaceXadvance=8;
        for(int c=32;c<127;c++) {
            BitmapFont.Glyph g=new BitmapFont.Glyph();g.id=c;g.width=7;g.height=12;g.xadvance=8;g.yoffset=-12;data.setGlyph(c,g);
        }
        BitmapFont font=new BitmapFont(data,new TextureRegion(texture),false);
        var cached=new IOSFittedTextCache();
        GlyphLayout layout=new GlyphLayout();
        Color color=new Color(.8f,.3f,.7f,1);
        for(boolean integers:new boolean[]{true,false}) {
            font.setUseIntegerPositions(integers);cached.clear();
            for(String text:new String[]{"CoronaBot$9","19.8","ALL IN","PENSANDO","9"})
                for(float width:new float[]{210,31.7f}) for(float x:new float[]{3.2f,-8.7f}) {
                    font.setColor(color.r,color.g,color.b,.53f);layout.setText(font,text);
                    float sx=data.scaleX,sy=data.scaleY;
                    float fit=CoronaPokerGdxTable.fittedSingleLineScale(layout.width,layout.height,width,23);
                    if(fit<1) { data.setScale(sx*fit,sy*fit);layout.setText(font,text); }
                    font.draw(batch,layout,x+(width-layout.width)/2f,7.3f+(23+layout.height)/2f);
                    float[] expected=Arrays.copyOf(batch.vertices,batch.count);
                    data.setScale(sx,sy);font.setColor(Color.WHITE);
                    cached.draw(font,text,batch,x,7.3f,width,23,color,.53f);
                    require(expected.length==batch.count,"Glyph count differs");
                    for(int i=0;i<expected.length;i++)require(Float.floatToIntBits(expected[i])==Float.floatToIntBits(batch.vertices[i]),"Glyph vertex/color differs: "+i);
                }
        }
        for(int i=0;i<20000;i++)cached.draw(font,"19.8",batch,3.2f,7.3f,210,23,color,.53f);
        ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId(),before=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<100000;i++)cached.draw(font,"19.8",batch,3.2f,7.3f,210,23,color,.53f);
        long bytes=bean.getThreadAllocatedBytes(thread)-before;
        require(bytes==0,"Steady seat text allocated "+bytes+" bytes");
        IOSSeatLabelKey key=new IOSSeatLabelKey();key.update(1,"es","CHECK","",10,"PASAR");
        before=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<100000;i++)sink=key.matches(1,"es","CHECK","",10)?key.text():null;
        require(bean.getThreadAllocatedBytes(thread)-before==0,"Stable label key allocated");
        require(!key.matches(1,"en","CHECK","",10),"Language fails to invalidate");
        cached.clear();font.dispose();texture.dispose();
        System.out.println("PASS seat cache: exact upstream vertices/colours under integer/fractional fitting; 100000 steady draws allocate "+bytes+" bytes");
    }
    public static final class CaptureBatch extends SpriteBatch {
        float[] vertices;int count;
        @Override public void draw(Texture texture,float[] values,int offset,int count) {
            System.arraycopy(values,offset,vertices,0,count);this.count=count;
        }
    }
    private static Object defaultValue(Class<?> type) {
        if(type==boolean.class)return false;if(type==int.class)return 0;if(type==float.class)return 0f;
        if(type==long.class)return 0L;if(type==double.class)return 0d;return null;
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
