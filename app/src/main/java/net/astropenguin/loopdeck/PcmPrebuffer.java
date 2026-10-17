package net.astropenguin.loopdeck;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** A bounded stereo prefix. After preparation, ownership passes to playback. */
final class PcmPrebuffer {
    interface Reader { int read(float[] buffer,int frames) throws IOException; }
    private final Reader reader;
    private final long limit;
    private final float[] prefix;
    private int filled, offset;
    private long consumed;
    PcmPrebuffer(Reader reader,int frames,long limit) {
        this.reader=reader; this.limit=limit; prefix=new float[Math.multiplyExact(frames,2)];
    }
    void prepare(BooleanSupplier cancelled) throws IOException {
        float[] scratch=new float[4096];
        while (filled<prefix.length && !cancelled.getAsBoolean()) {
            int requested=(int)Math.min(Math.min(scratch.length/2,(prefix.length-filled)/2),limit-filled/2);
            if (requested<=0) break;
            int n=reader.read(scratch,requested);
            if (n==0) break;
            System.arraycopy(scratch,0,prefix,filled,n*2); filled+=n*2;
        }
    }
    int read(float[] output,int frames) throws IOException {
        frames=(int)Math.min(frames,limit-consumed);
        if (frames<=0) return 0;
        int n;
        if (offset<filled) {
            n=Math.min(frames,(filled-offset)/2);
            System.arraycopy(prefix,offset,output,0,n*2); offset+=n*2;
        } else n=reader.read(output,frames);
        consumed+=n; return n;
    }
}
