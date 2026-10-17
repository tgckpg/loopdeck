package net.astropenguin.loopdeck;

import java.util.*;
import java.util.concurrent.*;

public final class GaplessTest {
    private static void expect(boolean value) { if (!value) throw new AssertionError(); }
    private static final class Source implements PcmPrebuffer.Reader {
        int position, calls; final int frames;
        Source(int frames) { this.frames=frames; }
        public int read(float[] output,int requested) {
            calls++; int n=Math.min(Math.min(requested,37),frames-position);
            for (int i=0;i<n;i++) { output[i*2]=position+i; output[i*2+1]=-(position+i); }
            position+=n; return n;
        }
    }
    private static void stream(int total,int prefix,long limit) throws Exception {
        Source source=new Source(total); PcmPrebuffer buffer=new PcmPrebuffer(source,prefix,limit);
        buffer.prepare(() -> false);
        int calls=source.calls, expected=0; float[] block=new float[202];
        Random random=new Random(1);
        while (true) {
            int n=buffer.read(block,1+random.nextInt(101));
            if (n==0) break;
            for (int i=0;i<n;i++) {
                expect(block[i*2]==expected && block[i*2+1]==-expected); expected++;
            }
            if (expected<Math.min(total,Math.min(prefix,limit))) expect(source.calls==calls);
        }
        expect(expected==Math.min(total,limit));
        expect(buffer.read(block,101)==0);
    }
    private static final class Resource implements AutoCloseable {
        int closed;
        public void close() { closed++; expect(closed==1); }
    }
    public static void main(String[] args) throws Exception {
        stream(12345,4410,Long.MAX_VALUE); // Prefix to live decoding, irregular reads.
        stream(12345,4410,5217); // An explicit CUE boundary must not leak samples.
        stream(113,4410,Long.MAX_VALUE); // Entire short track fits in the prefix.
        stream(12345,4410,173); // CUE track shorter than the prefix.
        stream(0,4410,Long.MAX_VALUE);
        Source source=new Source(12345);
        new PcmPrebuffer(source,4410,Long.MAX_VALUE).prepare(() -> true);
        expect(source.calls==0);
        PrefetchSlot<Resource> slot=new PrefetchSlot<>(); Resource result=new Resource();
        slot.offer(result); expect(slot.take()==result); slot.cancel(); expect(result.closed==0); result.close();
        slot=new PrefetchSlot<>(); result=new Resource(); slot.offer(result); slot.cancel(); slot.cancel(); expect(result.closed==1);
        slot=new PrefetchSlot<>(); slot.cancel(); result=new Resource(); slot.offer(result); expect(result.closed==1 && slot.take()==null);
        for (int i=0;i<200;i++) {
            final PrefetchSlot<Resource> race=new PrefetchSlot<>(); final Resource resource=new Resource();
            CountDownLatch start=new CountDownLatch(1);
            Thread producer=new Thread(() -> { try { start.await(); race.offer(resource); } catch (InterruptedException e) { throw new AssertionError(e); } });
            producer.start(); start.countDown(); race.cancel(); producer.join(); expect(resource.closed==1);
        }
        System.out.println("Gapless PCM continuity, boundaries, cancellation and ownership passed");
    }
}
