#include "tak1.h"
#include <algorithm>
#include <array>
#include <cstring>
#include <stdexcept>
#include <string>

namespace {
[[noreturn]] void invalid(const char* reason) {
    throw std::runtime_error(std::string("TAK 1.0: ") + reason);
}
void require(bool ok, const char* reason) { if (!ok) invalid(reason); }
int32_t wrap(uint32_t x) { int32_t v; std::memcpy(&v, &x, 4); return v; }
uint32_t magnitude(int32_t x) { return x < 0 ? 0U - uint32_t(x) : uint32_t(x); }
int32_t truncate(int32_t x, int shift) {
    uint32_t v = magnitude(x) >> shift;
    return wrap(x < 0 ? 0U - v : v);
}
int32_t predict(uint32_t sum, int shift) {
    int32_t signedSum = wrap(sum);
    uint32_t value = (magnitude(signedSum) + (1U << (shift - 1))) >> shift;
    value = std::min(value, 8191U);
    return signedSum < 0 ? -int32_t(value) : int32_t(value);
}
class Bits {
public:
    Bits(const uint8_t* data, size_t size) : data(data), size(size) {}
    uint32_t get(int n) {
        require(n >= 0 && n <= 32 && size <= SIZE_MAX / 8 && pos <= size*8 && size_t(n) <= size*8-pos, "truncated or invalid bitstream");
        uint64_t value = 0;
        const size_t first = pos / 8, end = (pos + n + 7) / 8;
        for (size_t i = first; i < end; ++i) value |= uint64_t(data[i]) << ((i-first)*8);
        value >>= pos & 7;
        pos += n;
        return uint32_t(value & (n == 32 ? UINT32_MAX : ((uint64_t(1)<<n)-1)));
    }
    int32_t signedValue(int n) {
        require(n > 0 && n <= 32, "invalid signed value size");
        uint32_t v = get(n), sign = 1U << (n-1);
        return wrap((v ^ sign)-sign);
    }
    int unary(int limit) { for (int i=0; i<limit; ++i) if (get(1)) return i; return limit; }
    void align() { require(get(int((0-pos)&7)) == 0, "nonzero frame padding"); }
    size_t position() const { return pos; }
private:
    const uint8_t* data;
    size_t size, pos = 0;
};
Tak1Decoder::Info streamInfo(Bits& b) {
    require(b.get(6) == 0, "different TAK codec type");
    b.get(4); // Encoder preset does not change the decoding rules.
    int frameType = int(b.get(4));
    b.get(32); b.get(3); // Whole-file sample count belongs to the demuxer.
    require(b.get(3) == 0, "unsupported sample type");
    Tak1Decoder::Info i{};
    i.rate = int(b.get(18)) + 6000;
    i.bits = int(b.get(5)) + 8;
    i.channels = int(b.get(4)) + 1;
    if (b.get(1)) {
        b.get(5);
        if (b.get(1)) for (int c=0; c<i.channels; ++c) b.get(6);
    }
    static constexpr int sizes[] = {3,4,6,8,4096,8192,16384,512,1024,2048};
    require(frameType < 10, "invalid frame duration");
    i.frameSamples = frameType < 4 ? i.rate*sizes[frameType]/32 : sizes[frameType];
    const int maximum = frameType < 4 ? 16384 : i.rate/4;
    require(i.frameSamples > 0 && i.frameSamples <= maximum && i.frameSamples <= 16384, "invalid frame sample count");
    require(i.bits >= 8 && i.bits <= 24 && i.channels <= 2, "unsupported PCM format");
    return i;
}
uint32_t crc24(const uint8_t* data, size_t size) {
    static const auto table = [] {
        std::array<uint32_t,256> t{};
        for (unsigned i=0; i<256; ++i) {
            uint32_t v = i << 16;
            for (int j=0; j<8; ++j) v = ((v<<1) ^ ((v & 0x800000U) ? 0x864CFBU : 0)) & 0xFFFFFFU;
            t[i] = v;
        }
        return t;
    }();
    uint32_t crc = 0xB704CEU;
    for (size_t i=0; i<size; ++i) crc = ((crc<<8) ^ table[((crc>>16)^data[i])&255]) & 0xFFFFFFU;
    return crc;
}
void checkCrc(const uint8_t* data, size_t size) {
    require(size >= 3, "missing checksum");
    uint32_t stored = uint32_t(data[size-3]) | uint32_t(data[size-2])<<8 | uint32_t(data[size-1])<<16;
    require(crc24(data,size-3) == stored, "frame checksum mismatch");
}
struct Code { int width, outer, inner, extended, step, bias; };
// Numeric codebook parameters recovered from the TAK 1.0 decoder interface.
constexpr Code codes[47] = {
    {3,6,4,8,4,44},
    {4,14,10,18,4,54},
    {4,12,4,20,8,92},
    {5,28,22,34,8,106},
    {5,26,12,40,12,148},
    {6,56,44,68,16,212},
    {6,52,24,80,24,296},
    {7,112,88,136,32,424},
    {7,104,48,160,48,592},
    {8,224,176,272,64,848},
    {8,208,96,320,96,1184},
    {9,448,352,544,128,1696},
    {9,416,192,640,192,2368},
    {10,896,704,1088,256,3392},
    {10,832,384,1280,384,4736},
    {11,1792,1408,2176,512,6784},
    {11,1664,768,2560,768,9472},
    {12,3584,2816,4352,1024,13568},
    {12,3328,1536,5120,1536,18944},
    {13,7168,5632,8704,2048,27136},
    {13,6656,3072,10240,3072,37888},
    {14,14336,11264,17408,4096,54272},
    {14,13312,6144,20480,6144,75776},
    {15,28672,22528,34816,8192,108544},
    {15,26624,12288,40960,12288,151552},
    {16,57344,45056,69632,16384,217088},
    {16,53248,24576,81920,24576,303104},
    {17,114688,90112,139264,32768,434176},
    {17,106496,49152,163840,49152,606208},
    {18,229376,180224,278528,65536,868352},
    {18,212992,98304,327680,98304,1212416},
    {19,458752,360448,557056,131072,1736704},
    {19,425984,196608,655360,196608,2424832},
    {20,917504,720896,1114112,262144,3473408},
    {20,851968,393216,1310720,393216,4849664},
    {21,1835008,1441792,2228224,524288,6946816},
    {21,1703936,786432,2621440,786432,9699328},
    {22,3670016,2883584,4456448,1048576,13893632},
    {22,3407872,1572864,5242880,1572864,19398656},
    {23,7340032,5767168,8912896,2097152,27787264},
    {23,6815744,3145728,10485760,3145728,38797312},
    {24,14680064,11534336,17825792,4194304,55574528},
    {24,13631488,6291456,20971520,6291456,77594624},
    {25,29360128,23068672,35651584,8388608,111149056},
    {25,27262976,12582912,41943040,12582912,155189248},
    {26,58720256,46137344,71303168,16777216,222298112},
    {26,54525952,25165824,83886080,25165824,310378496},
};
constexpr int orders[16] = {4,8,12,16,24,32,48,64,80,96,112,128,160,192,224,256};
int ceilLog2(uint32_t value) {
    if (value <= 1) return int(value);
    int n=0; for (--value; value; value>>=1) ++n; return n;
}
std::vector<int> boundaries(int rate, int samples, int alignment, int divider, int maxCount) {
    std::vector<int> t{0};
    for (int i=1; i<=maxCount; ++i) {
        int v = (rate*i/divider+alignment/2) & -alignment;
        t.push_back(v);
        if (v >= samples) break;
    }
    if (t.back() < samples) t.back() = samples;
    else if (t.back() > samples) {
        t.back() = samples;
        if (t.back()-t[t.size()-2] < alignment) t.erase(t.end()-2);
    }
    return t;
}
struct Filter { std::vector<int32_t> coefficients; int quant = 0, shift = 0; };
struct Channel { std::vector<int32_t> data; int shift = 0; Filter prefilter; };
class Payload {
public:
    Payload(const uint8_t* data, size_t bytes, const Tak1Decoder::Info& info, int samples)
        : bits(data,bytes), info(info), n(samples), channelTable(boundaries(info.rate,samples,8,256,64)),
          residueTable(boundaries(info.rate,samples,4,512,128)) {}
    Tak1Decoder::Audio decode() {
        Tak1Decoder::Audio audio; audio.samples = n;
        Channel ch[2];
        if (n < 16) {
            for (int c=0; c<info.channels; ++c) {
                ch[c].data.resize(n);
                for (auto& v : ch[c].data) v = bits.signedValue(info.bits);
            }
        } else {
            int mode = info.channels == 2 ? int(bits.get(3)) : 0;
            require(mode <= 5, "invalid stereo mode");
            int first = mode == 2 || mode == 4 ? 1 : 0;
            ch[first] = channel();
            Filter jointFilter; bool jointHead=false, jointTail=false;
            if (mode == 1 || mode == 2) {
                jointHead = bits.get(1); jointTail = bits.get(1);
                int shift = int(bits.get(4));
                require(bits.get(2) == 1, "invalid stereo filter");
                jointFilter = coefficients(8); jointFilter.shift = shift;
            }
            if (info.channels == 2) {
                ch[1-first] = channel();
                if (mode >= 3) require(ch[0].prefilter.coefficients.empty() && ch[1].prefilter.coefficients.empty(), "incompatible stereo prefilter");
                joint(mode,ch,jointFilter,jointHead,jointTail);
            }
            for (int c=0; c<info.channels; ++c) restore(ch[c]);
        }
        bits.align();
        for (int c=0; c<info.channels; ++c) audio.channel[c] = std::move(ch[c].data);
        return audio;
    }
    size_t consumedBytes() const { return bits.position()/8; }
private:
    Bits bits;
    const Tak1Decoder::Info& info;
    int n;
    std::vector<int> channelTable, residueTable;
    void segment(int mode, int escapeBits, int32_t* output, int count) {
        require(mode >= 0 && mode <= 50 && count >= 0 && count <= n, "invalid residual segment");
        if (!mode) { std::fill(output,output+count,0); return; }
        for (int i=0; i<count; ++i) {
            uint32_t v;
            if (mode < 4) {
                int base = 1 << (mode-1);
                v = bits.get(mode);
                if (v) output[i] = int(v) <= base ? int32_t(v)-1 : -(int32_t(v)-base);
                else {
                    int q = bits.unary(4);
                    if (q < 4) {
                        int sign = int(bits.get(1));
                        output[i] = sign ? -(q+base) : q+base;
                    } else {
                        require(escapeBits > 0 && escapeBits <= 31, "invalid residual escape");
                        v = bits.get(escapeBits);
                        uint32_t half = 1U << (escapeBits-1);
                        output[i] = wrap(v < half ? v+base+4 : 0U-(v+base+4-half));
                    }
                }
            } else {
                const auto& code = codes[mode-4];
                v = bits.get(code.width);
                if (int(v) < code.inner) output[i] = wrap((v>>1) ^ (0U-(v&1)));
                else {
                    uint32_t sign = bits.get(1);
                    if (int(v) < code.outer) v += code.inner/2-code.inner;
                    else {
                        int q = bits.unary(9);
                        if (q < 9) v += code.extended/2-code.outer + uint32_t(code.step/2)*q;
                        else {
                            require(escapeBits <= 42, "invalid residual escape");
                            uint32_t extra = escapeBits > 10 ? bits.get(escapeBits-10) : 0;
                            v += code.bias/2-code.outer + uint32_t(code.step/2)*extra;
                        }
                    }
                    output[i] = wrap(v ^ (0U-sign));
                }
            }
        }
    }
    void residues(int32_t* output, int count) {
        require(count >= 0 && count <= n, "invalid residual count");
        if (!bits.get(1)) {
            int mode = int(bits.get(6));
            int escape = mode ? int(bits.get(5)) : 1;
            require(!mode || (escape > 0 && escape <= 28), "invalid residual escape");
            segment(mode,escape,output,count); return;
        }
        int parts = 0;
        while (parts+1 < int(residueTable.size()) && residueTable[parts+1] <= count) ++parts;
        if (!parts || count-residueTable[parts] >= 4) ++parts;
        require(parts > 1 && parts <= 128 && parts < int(residueTable.size()), "invalid residual partition count");
        std::array<int,128> modes{};
        int format = int(bits.get(2));
        if (!format) {
            int base = int(bits.get(6)), width = int(bits.get(3))+1;
            for (int i=0; i<parts; ++i) modes[i] = base+int(bits.get(width));
        } else {
            require(format <= 2, "invalid residual mode table");
            modes[0] = int(bits.get(6));
            for (int i=1; i<parts; ++i) {
                int mode = modes[i-1];
                if (format == 1) {
                    int q = bits.unary(9);
                    if (q == 9) mode = int(bits.get(6));
                    else if (q) mode += bits.get(1) ? -q : q;
                } else {
                    int v = int(bits.get(2));
                    if (!v) {
                        int q = bits.unary(7);
                        if (q == 7) mode = int(bits.get(6));
                        else mode += bits.get(1) ? -(q+2) : q+2;
                    } else if (v == 2) --mode;
                    else if (v == 3) ++mode;
                }
                modes[i] = mode;
            }
        }
        int maxBits = int(bits.get(5));
        require(maxBits <= 28, "invalid residual amplitude");
        uint32_t limit = maxBits ? 1U << maxBits : 0;
        int i=0;
        while (i<parts) {
            int mode = modes[i], start = i;
            require(mode >= 0 && mode <= 50, "invalid residual coding mode");
            while (i<parts && modes[i] == mode) ++i;
            int end = i == parts ? count : residueTable[i];
            int length = end-residueTable[start], escape = 1;
            if (mode > 0 && mode < 4) {
                int base = 1 << (mode-1);
                if (limit > uint32_t(base+3)) escape = ceilLog2(limit-base-3)+1;
            } else if (mode >= 4) {
                const auto& code = codes[mode-4];
                escape = limit*2 < uint32_t(code.bias+code.step) ? 10 : ceilLog2((limit*2-code.bias)/code.step+1)+10;
            }
            segment(mode,escape,output+residueTable[start],length);
        }
    }
    Filter coefficients(int count) {
        require(count > 0 && count <= 256 && !(count&3), "invalid predictor order");
        Filter f; f.quant = int(bits.get(3));
        bool differential = count >= 12 && bits.get(1);
        f.coefficients.resize(count);
        int base=0, deltaBits=0;
        if (count > 16) { base = int(bits.get(4)); deltaBits = int(bits.get(1))+2; }
        for (int i=0; i<count; i+=4) {
            int width = count <= 16 ? int(bits.get(4)) : base-int(bits.get(deltaBits));
            require(width > 0 && width <= 15, "invalid predictor coefficient size");
            for (int j=0; j<4; ++j) f.coefficients[i+j] = bits.signedValue(width);
        }
        if (differential) {
            for (int i=1; i<count; ++i) f.coefficients[i] = wrap(uint32_t(f.coefficients[i])-uint32_t(f.coefficients[i-1]));
            for (int32_t v : f.coefficients) require(magnitude(v) <= 8191, "invalid differential predictor");
        }
        return f;
    }
    void lpc(int32_t* x, int count, int mode) {
        require(mode <= 2, "invalid residual prediction mode");
        for (int level=0; level<mode; ++level)
            for (int i=mode-level; i<count; ++i) x[i] = wrap(uint32_t(x[i])+uint32_t(x[i-1]));
    }
    void fir(int32_t* x, int count, const Filter& f) {
        int order = int(f.coefficients.size());
        require(count >= order, "predictor exceeds subframe");
        std::vector<int32_t> history(count);
        for (int i=0; i<order; ++i) history[i] = truncate(x[i],f.shift);
        for (int i=order; i<count; ++i) {
            uint32_t sum = 0;
            for (int j=0; j<order; ++j) sum += uint32_t(history[i-order+j])*uint32_t(f.coefficients[j]);
            int32_t prediction = predict(sum,10-f.quant);
            x[i] = wrap((uint32_t(prediction)<<f.shift)-uint32_t(x[i]));
            history[i] = truncate(x[i],f.shift);
        }
    }
    void plain(int32_t* x, int count) {
        int mode = int(bits.get(2)); residues(x,count); lpc(x,count,mode);
    }
    void subframe(int32_t* x, int count) {
        if (!bits.get(1)) { plain(x,count); return; }
        int shift = int(bits.get(4)), order = orders[bits.get(4)];
        require(order <= count, "predictor exceeds subframe");
        plain(x,order);
        Filter f = coefficients(order); f.shift = shift;
        residues(x+order,count-order); fir(x,count,f);
    }
    Channel channel() {
        Channel c;
        c.shift = bits.get(1) ? int(bits.get(4))+1 : 0;
        require(c.shift < info.bits, "invalid sample shift");
        c.data.resize(n);
        c.data[0] = wrap(uint32_t(bits.signedValue(info.bits-c.shift)) << c.shift);
        if (bits.get(1)) {
            int shift = int(bits.get(4));
            require(bits.get(2) == 0, "invalid channel prefilter");
            c.prefilter = coefficients(4); c.prefilter.shift = shift;
        }
        int parts = int(bits.get(3))+1;
        require(parts <= 5, "invalid channel partition count");
        int offsets[6]{};
        for (int i=1; i<parts; ++i) {
            int index = int(bits.get(6));
            require(index > 0 && index < int(channelTable.size())-1, "invalid channel partition index");
            offsets[i] = channelTable[index];
            require(offsets[i] > offsets[i-1], "unordered channel partitions");
        }
        offsets[parts] = n-1;
        for (int i=0; i<parts; ++i) {
            int length = offsets[i+1]-offsets[i];
            require(length > 0, "empty channel partition");
            subframe(c.data.data()+1+offsets[i],length);
        }
        return c;
    }
    void restore(Channel& c) {
        if (!c.prefilter.coefficients.empty()) fir(c.data.data()+1,n-1,c.prefilter);
        for (int i=1; i<n; ++i) c.data[i] = wrap(uint32_t(c.data[i]) << c.shift);
        for (int i=1; i<n; ++i) c.data[i] = wrap(uint32_t(c.data[i])+uint32_t(c.data[i-1]));
    }
    void joint(int mode, Channel* ch, const Filter& filter, bool head, bool tail) {
        if (!mode) return;
        auto& left = ch[0].data; auto& right = ch[1].data;
        if (mode <= 2) {
            require(n-1 >= 256, "stereo predictor needs a longer frame");
            const auto& source = mode == 1 ? left : right;
            auto& target = mode == 1 ? right : left;
            const int start=4, body=n-1-7;
            for (int i=0; i<start; ++i) if (head) target[1+i] = wrap(uint32_t(target[1+i])+uint32_t(source[1+i]));
            for (int i=start+body; i<n-1; ++i) if (tail) target[1+i] = wrap(uint32_t(target[1+i])+uint32_t(source[1+i]));
            for (int i=0; i<body; ++i) {
                uint32_t sum=0;
                for (int j=0; j<8; ++j) sum += uint32_t(truncate(source[1+i+j],filter.shift))*uint32_t(filter.coefficients[j]);
                target[1+start+i] = wrap((uint32_t(predict(sum,10))<<filter.shift)-uint32_t(target[1+start+i]));
            }
        } else {
            for (int i=1; i<n; ++i) {
                if (mode == 3) right[i] = wrap(uint32_t(right[i])+uint32_t(left[i]));
                else if (mode == 4) left[i] = wrap(uint32_t(right[i])-uint32_t(left[i]));
                else {
                    uint32_t mid = uint32_t(left[i])*2+(uint32_t(right[i])&1);
                    uint32_t difference = mid-uint32_t(right[i]);
                    uint32_t sum = mid+uint32_t(right[i]);
                    left[i] = wrap((difference>>1) | (difference & 0x80000000U));
                    right[i] = wrap((sum>>1) | (sum & 0x80000000U));
                }
            }
        }
    }
};
}
Tak1Decoder::Tak1Decoder(const uint8_t* data, size_t size) {
    Bits b(data,size); initial = current = streamInfo(b);
}
Tak1Decoder::Audio Tak1Decoder::decode(const uint8_t* packet, size_t size) {
    Bits b(packet,size);
    Info frameInfo = current;
    require(b.get(16) == 0xA0FF, "missing frame sync");
    uint32_t flags = b.get(3), number = b.get(21);
    int samples = 0;
    if (flags&1) { samples = int(b.get(14))+1; require(b.get(2) == 0, "invalid last frame flags"); }
    if (flags&2) {
        Info parsed = streamInfo(b);
        require(parsed.rate == initial.rate && parsed.bits == initial.bits && parsed.channels == initial.channels, "PCM format changes inside the track");
        frameInfo = parsed;
        if (b.get(6)) b.get(25);
        b.align();
    }
    require(!(flags&4), "unsupported per-frame metadata");
    b.get(24);
    size_t header = b.position()/8;
    checkCrc(packet,header);
    // Verify compressed data before allowing it to drive allocations or filters.
    checkCrc(packet+header,size-header);
    samples = samples ? samples : frameInfo.frameSamples;
    require(samples > 0 && samples <= frameInfo.frameSamples, "invalid last frame size");
    Payload payload(packet+header,size-header,frameInfo,samples);
    Audio audio = payload.decode();
    size_t bytes = payload.consumedBytes()+3;
    require(bytes == size-header, "unexpected frame payload length");
    audio.frameNumber = number;
    current = frameInfo;
    return audio;
}
