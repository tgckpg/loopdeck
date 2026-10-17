package net.astropenguin.loopdeck;

import java.io.IOException;

final class NativeDecoder {
    static { System.loadLibrary("avutil"); System.loadLibrary("swresample");
        System.loadLibrary("avcodec"); System.loadLibrary("avformat"); System.loadLibrary("loopdeck"); }
    static native long open(int fd) throws IOException;
    static native void close(long handle);
    static native int sourceRate(long handle) throws IOException;
    static native long duration(long handle) throws IOException;
    static native String cueSheet(long handle) throws IOException;
    static native String[] chapters(long handle) throws IOException;
    static native String description(long handle) throws IOException;
    static native void outputRate(long handle, int rate) throws IOException;
    static native int read(long handle, float[] buffer, int frames) throws IOException;
    static native void seek(long handle, long outputFrame) throws IOException;
    private NativeDecoder() {}
}
