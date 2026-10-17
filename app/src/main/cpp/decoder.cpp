#include "decoder.h"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <numeric>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include <libavutil/opt.h>
}

Decoder::Decoder(int inputFd) {
    fd = dup(inputFd);
    if (fd < 0) throw std::runtime_error("Cannot duplicate file descriptor");
}
Decoder::~Decoder() {
    av_packet_free(&packet);
    av_frame_free(&frame);
    swr_free(&swr);
    avcodec_free_context(&codec);
    avformat_close_input(&format);
    if (io) { av_freep(&io->buffer); avio_context_free(&io); }
    if (fd >= 0) close(fd);
}
void Decoder::check(int result, const char* operation) {
    if (result >= 0) return;
    if (result == AVERROR_PATCHWELCOME && codec && codec->codec_id == AV_CODEC_ID_TAK)
        throw std::runtime_error("This TAK codec type is not supported by the available decoders.");
    char detail[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(result, detail, sizeof(detail));
    throw std::runtime_error(std::string(operation) + ": " + detail);
}
int Decoder::readFd(void* opaque, uint8_t* data, int size) {
    auto* d = static_cast<Decoder*>(opaque);
    ssize_t count;
    do { count = ::read(d->fd, data, size); } while (count < 0 && errno == EINTR);
    return count > 0 ? static_cast<int>(count) : count == 0 ? AVERROR_EOF : AVERROR(errno);
}
int64_t Decoder::seekFd(void* opaque, int64_t offset, int whence) {
    auto* d = static_cast<Decoder*>(opaque);
    if (whence & AVSEEK_SIZE) {
        struct stat info {};
        return fstat(d->fd, &info) == 0 && S_ISREG(info.st_mode) ? info.st_size : AVERROR(ENOSYS);
    }
    const off_t result = lseek(d->fd, offset, whence & ~AVSEEK_FORCE);
    return result < 0 ? AVERROR(errno) : result;
}
void Decoder::open() {
    format = avformat_alloc_context();
    if (!format) throw std::bad_alloc();
    auto* buffer = static_cast<unsigned char*>(av_malloc(65536));
    if (!buffer) throw std::bad_alloc();
    io = avio_alloc_context(buffer, 65536, 0, this, readFd, nullptr, seekFd);
    if (!io) { av_free(buffer); throw std::bad_alloc(); }
    format->pb = io;
    format->flags |= AVFMT_FLAG_CUSTOM_IO;
    check(avformat_open_input(&format, nullptr, nullptr, nullptr), "Open file");
    // TAK's demuxer exposes STREAMINFO directly. Avoid probing codec type 0
    // through FFmpeg's unsupported decoder before selecting our native path.
    const auto* first = format->nb_streams == 1 ? format->streams[0]->codecpar : nullptr;
    const bool legacyTak = first && first->codec_id == AV_CODEC_ID_TAK && first->extradata_size >= 10
        && (first->extradata[0] & 63) == 0;
    if (!legacyTak) check(avformat_find_stream_info(format, nullptr), "Read stream information");
    const AVCodec* decoder = nullptr;
    streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    check(streamIndex, "Find supported audio stream");
    stream = format->streams[streamIndex];
    codec = avcodec_alloc_context3(decoder);
    if (!codec) throw std::bad_alloc();
    check(avcodec_parameters_to_context(codec, stream->codecpar), "Read codec parameters");
    codec->pkt_timebase=stream->time_base;
    codec->thread_count = 2;
    if (legacyTak) {
        tak1 = std::make_unique<Tak1Decoder>(stream->codecpar->extradata, stream->codecpar->extradata_size);
        const auto& info = tak1->info();
        codec->sample_rate = info.rate;
        codec->sample_fmt = AV_SAMPLE_FMT_S32P;
        codec->bits_per_raw_sample = info.bits;
        av_channel_layout_uninit(&codec->ch_layout);
        av_channel_layout_default(&codec->ch_layout, info.channels);
    } else check(avcodec_open2(codec, decoder, nullptr), "Open audio decoder");
    if (codec->sample_rate <= 0) throw std::runtime_error("Invalid audio sample rate");
    frame = av_frame_alloc();
    packet = av_packet_alloc();
    if (!frame || !packet) throw std::bad_alloc();
    outputRate(codec->sample_rate);
}
void Decoder::outputRate(int value) {
    if (value <= 0) throw std::runtime_error("Invalid output rate");
    rate = value;
    swr_free(&swr);
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
    check(swr_alloc_set_opts2(&swr, &stereo, AV_SAMPLE_FMT_FLT, rate,
        &codec->ch_layout, codec->sample_fmt, codec->sample_rate, 0, nullptr), "Configure PCM conversion");
    check(swr_init(swr), "Initialize PCM conversion");
}
int64_t Decoder::durationMs() const {
    if (stream->duration != AV_NOPTS_VALUE)
        return av_rescale_q(stream->duration, stream->time_base, AVRational{1,1000});
    return format->duration == AV_NOPTS_VALUE ? 0 : format->duration / 1000;
}
std::string Decoder::description() const {
    const auto* desc = avcodec_descriptor_get(codec->codec_id);
    int bits = tak1 ? tak1->info().bits : stream->codecpar->bits_per_raw_sample;
    return std::string(tak1 ? "TAK 1.0 (native)" : desc ? desc->name : "audio") + " · " + std::to_string(codec->sample_rate)
        + " Hz · " + std::to_string(codec->ch_layout.nb_channels) + " ch"
        + (bits > 0 ? " · " + std::to_string(bits) + " bit" : "");
}
int Decoder::receiveFrame() {
    if (!tak1) return avcodec_receive_frame(codec, frame);
    while (true) {
        int result = av_read_frame(format, packet);
        if (result < 0) return result;
        if (packet->stream_index != streamIndex) { av_packet_unref(packet); continue; }
        auto audio = tak1->decode(packet->data, packet->size);
        frame->format = AV_SAMPLE_FMT_S32P;
        frame->sample_rate = codec->sample_rate;
        frame->nb_samples = audio.samples;
        frame->pts = frame->best_effort_timestamp = packet->pts;
        check(av_channel_layout_copy(&frame->ch_layout, &codec->ch_layout), "Copy TAK channel layout");
        check(av_frame_get_buffer(frame, 0), "Allocate TAK PCM frame");
        const int shift = 32-tak1->info().bits;
        for (int c=0; c<codec->ch_layout.nb_channels; ++c) {
            auto* destination = reinterpret_cast<int32_t*>(frame->data[c]);
            for (int i=0; i<audio.samples; ++i) {
                uint32_t value = uint32_t(audio.channel[c][i]) << shift;
                std::memcpy(destination+i, &value, sizeof(value));
            }
        }
        av_packet_unref(packet);
        return 0;
    }
}
bool Decoder::fill() {
    pending.clear(); pendingOffset = 0;
    while (true) {
        int result = receiveFrame();
        if (result == AVERROR_EOF) {
            if (resamplerEnded) return false;
            resamplerEnded = true;
            const int capacity = swr_get_out_samples(swr, 0);
            pending.resize(static_cast<size_t>(std::max(capacity, 1)) * 2);
            uint8_t* out[] = {reinterpret_cast<uint8_t*>(pending.data())};
            int n = swr_convert(swr, out, capacity, nullptr, 0);
            check(n, "Drain audio converter");
            pending.resize(n * 2);
            return n > 0;
        }
        if (result >= 0) {
            if (!resamplerStarted) {
                int64_t sourceStart = av_rescale_q(decodedNext, AVRational{1,rate}, AVRational{1,codec->sample_rate});
                if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
                    int64_t origin = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
                    sourceStart = av_rescale_q(frame->best_effort_timestamp-origin,stream->time_base,AVRational{1,codec->sample_rate});
                }
                if (timelineOrigin == AV_NOPTS_VALUE) timelineOrigin=sourceStart;
                sourceStart-=timelineOrigin;
                if ((codec->codec_id==AV_CODEC_ID_WMAV1 || codec->codec_id==AV_CODEC_ID_WMAV2) && frame->nb_samples>0) {
                    // ASF timestamps are milliseconds, while WMA v1/v2 emits
                    // fixed-sized sample frames. Recover the exact frame boundary.
                    int64_t size=frame->nb_samples;
                    sourceStart=((sourceStart+size/2)/size)*size;
                }
                // Restore the global rational resampling phase after a seek.
                // Without this, e.g. 44.1 -> 48 kHz seeks shift by a fractional sample.
                int period = codec->sample_rate / std::gcd(codec->sample_rate,rate);
                int prefix = static_cast<int>((sourceStart % period + period) % period);
                if (prefix) check(swr_inject_silence(swr,prefix),"Align audio converter");
                decodedNext = av_rescale_q(sourceStart-prefix,AVRational{1,codec->sample_rate},AVRational{1,rate});
                resamplerStarted=true;
            }
            int capacity = swr_get_out_samples(swr, frame->nb_samples);
            pending.resize(static_cast<size_t>(std::max(capacity, 1)) * 2);
            uint8_t* out[] = {reinterpret_cast<uint8_t*>(pending.data())};
            int n = swr_convert(swr, out, capacity, const_cast<const uint8_t**>(frame->extended_data), frame->nb_samples);
            check(n, "Convert decoded audio");
            int64_t start = decodedNext;
            decodedNext = start + n;
            pending.resize(n * 2);
            pendingOffset = static_cast<size_t>(std::clamp<int64_t>(skipUntil - start, 0, n)) * 2;
            av_frame_unref(frame);
            if (pendingOffset < pending.size()) return true;
            continue;
        }
        if (result != AVERROR(EAGAIN)) check(result, "Decode audio");
        if (inputEnded) {
            if (drainSent) throw std::runtime_error("Decoder stalled while draining");
            check(avcodec_send_packet(codec, nullptr), "Drain decoder");
            drainSent = true;
            continue;
        }
        while (true) {
            result = av_read_frame(format, packet);
            if (result == AVERROR_EOF) { inputEnded = true; break; }
            check(result, "Read audio packet");
            if (packet->stream_index == streamIndex) {
                result = avcodec_send_packet(codec, packet);
                av_packet_unref(packet);
                check(result, "Submit audio packet");
                break;
            }
            av_packet_unref(packet);
        }
    }
}
int Decoder::read(float* output, int frames) {
    if (frames <= 0) return 0;
    int copied = 0;
    while (copied < frames) {
        if (pendingOffset == pending.size() && !fill()) break;
        size_t count = std::min(pending.size() - pendingOffset, static_cast<size_t>((frames - copied) * 2));
        std::memcpy(output + copied * 2, pending.data() + pendingOffset, count * sizeof(float));
        pendingOffset += count;
        copied += static_cast<int>(count / 2);
    }
    return copied;
}
void Decoder::seek(int64_t outputFrame) {
    // Establish the decoded timeline origin. Some containers (notably ASF/WMA)
    // start decoded audio later than their stream start timestamp.
    if (timelineOrigin == AV_NOPTS_VALUE && !fill()) throw std::runtime_error("No decoded audio");
    int64_t origin = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
    // Give predictive codecs and resampling filters enough history before A.
    int64_t preroll=std::max<int64_t>(0,outputFrame-int64_t(rate)*2);
    int64_t target = preroll==0 ? origin : av_rescale_q(preroll, AVRational{1,rate}, stream->time_base)
        + av_rescale_q(timelineOrigin,AVRational{1,codec->sample_rate},stream->time_base) + origin;
    check(avformat_seek_file(format, streamIndex, INT64_MIN, target, target, 0), "Seek audio");
    if (tak1) tak1->reset();
    else avcodec_flush_buffers(codec);
    av_packet_unref(packet);
    av_frame_unref(frame);
    swr_close(swr);
    check(swr_init(swr), "Reset audio converter");
    inputEnded = drainSent = resamplerEnded = resamplerStarted = false;
    pending.clear(); pendingOffset = 0;
    skipUntil = decodedNext = outputFrame;
}

std::string Decoder::cueSheet() const {
    auto* tag=av_dict_get(format->metadata,"cuesheet",nullptr,0);
    if (!tag) tag=av_dict_get(stream->metadata,"cuesheet",nullptr,0);
    return tag ? tag->value : "";
}
std::vector<std::string> Decoder::chapters() const {
    std::vector<std::string> result;
    for (unsigned i=0;i<format->nb_chapters;++i) {
        const auto* chapter=format->chapters[i];
        auto start=av_rescale_q(chapter->start,chapter->time_base,AVRational{1,1000});
        auto end=av_rescale_q(chapter->end,chapter->time_base,AVRational{1,1000});
        auto* title=av_dict_get(chapter->metadata,"title",nullptr,0);
        result.push_back(std::to_string(start));
        result.push_back(std::to_string(end));
        result.push_back(title ? title->value : "Track "+std::to_string(i+1));
    }
    return result;
}
