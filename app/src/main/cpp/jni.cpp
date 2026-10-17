#include "decoder.h"
#include <jni.h>
#include <memory>
#include <stdexcept>

static Decoder* decoder(jlong handle) {
    if (!handle) throw std::runtime_error("No file is open");
    return reinterpret_cast<Decoder*>(handle);
}
static void report(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/io/IOException"), e.what());
}
extern "C" JNIEXPORT jlong JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_open(JNIEnv* env, jclass, jint fd) {
    try {
        auto d = std::make_unique<Decoder>(fd); d->open();
        return reinterpret_cast<jlong>(d.release());
    } catch (const std::exception& e) { report(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_close(JNIEnv*, jclass, jlong handle) { delete reinterpret_cast<Decoder*>(handle); }
extern "C" JNIEXPORT jint JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_sourceRate(JNIEnv* env, jclass, jlong handle) {
    try { return decoder(handle)->sourceRate(); } catch (const std::exception& e) { report(env,e); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_duration(JNIEnv* env, jclass, jlong handle) {
    try { return decoder(handle)->durationMs(); } catch (const std::exception& e) { report(env,e); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_description(JNIEnv* env, jclass, jlong handle) {
    try { return env->NewStringUTF(decoder(handle)->description().c_str()); } catch (const std::exception& e) { report(env,e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_outputRate(JNIEnv* env, jclass, jlong handle, jint rate) {
    try { decoder(handle)->outputRate(rate); } catch (const std::exception& e) { report(env,e); }
}
extern "C" JNIEXPORT void JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_seek(JNIEnv* env, jclass, jlong handle, jlong frame) {
    try { decoder(handle)->seek(frame); } catch (const std::exception& e) { report(env,e); }
}
extern "C" JNIEXPORT jint JNICALL
Java_net_astropenguin_loopdeck_NativeDecoder_read(JNIEnv* env, jclass, jlong handle, jfloatArray array, jint frames) {
    try {
        auto* d = decoder(handle);
        if (frames < 0 || frames > env->GetArrayLength(array) / 2) throw std::runtime_error("Invalid PCM buffer size");
        // Avoid pinning a Java array across file IO and codec work.
        thread_local std::vector<float> scratch;
        scratch.resize(static_cast<size_t>(frames) * 2);
        int count = d->read(scratch.data(), frames);
        env->SetFloatArrayRegion(array, 0, count * 2, scratch.data());
        return count;
    } catch (const std::exception& e) { report(env,e); return 0; }
}
