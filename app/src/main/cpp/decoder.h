#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include "tak1.h"
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
}

// Owned by the playback worker only. The descriptor is duplicated, never copied
// into a temporary file, so album-sized files on removable storage are supported.
class Decoder {
public:
    explicit Decoder(int inputFd);
    ~Decoder();
    Decoder(const Decoder&) = delete;
    Decoder& operator=(const Decoder&) = delete;
    void open();
    void outputRate(int rate);
    int read(float* output, int frames);
    void seek(int64_t outputFrame);
    int sourceRate() const { return codec->sample_rate; }
    int64_t durationMs() const;
    std::string description() const;
private:
    int fd = -1;
    AVFormatContext* format = nullptr;
    AVIOContext* io = nullptr;
    AVCodecContext* codec = nullptr;
    SwrContext* swr = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    AVStream* stream = nullptr;
    int streamIndex = -1, rate = 0;
    bool inputEnded = false, drainSent = false, resamplerEnded = false, resamplerStarted = false;
    std::vector<float> pending;
    size_t pendingOffset = 0;
    int64_t skipUntil = 0, decodedNext = 0;
    int64_t timelineOrigin = AV_NOPTS_VALUE;
    std::unique_ptr<Tak1Decoder> tak1;
    int receiveFrame();
    bool fill();
    void check(int result, const char* operation);
    static int readFd(void* opaque, uint8_t* data, int size);
    static int64_t seekFd(void* opaque, int64_t offset, int whence);
};
