package net.astropenguin.loopdeck;

public final class LoopMathTest {
    private static void expect(long actual,long expected) {
        if (actual!=expected) throw new AssertionError("Expected "+expected+", got "+actual);
    }
    public static void main(String[] args) {
        expect(LoopMath.position(1000,499,1000,1500,true),1499);
        expect(LoopMath.position(1000,500,1000,1500,true),1000);
        expect(LoopMath.position(1200,1301,1000,1500,true),1001);
        expect(LoopMath.position(1000,500,1000,1500,false),1500);
        expect(LoopMath.position(0,(1L<<32)+17,-1,-1,false),(1L<<32)+17);
        expect(LoopMath.cacheSamples(LoopMath.CACHE_BYTES/8),LoopMath.CACHE_BYTES/4);
        expect(LoopMath.cacheSamples(LoopMath.CACHE_BYTES/8+1),0);
        expect(LoopMath.cacheSamples(0),0);
        expect(LoopMath.frame(1000,192000),192000);
        expect(LoopMath.millis(192000,192000),1000);
        if (LoopMath.valid(100,100) || LoopMath.valid(-1,100) || !LoopMath.valid(100,101)) throw new AssertionError("Invalid loop validation");
        System.out.println("Loop frame arithmetic passed");
    }
}
