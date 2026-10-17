#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

// Native TAK 1.0 (codec type 0) decoder. No Windows DLL or CPU emulation is used.
// FFmpeg continues to provide container parsing, packets, timestamps and seeking.
class Tak1Decoder {
public:
    struct Info { int rate, bits, channels, frameSamples; };
    struct Audio {
        int samples = 0;
        uint32_t frameNumber = 0;
        // Signed PCM in the original bit depth, before AudioTrack conversion.
        std::vector<int32_t> channel[2];
    };
    Tak1Decoder(const uint8_t* streamInfo, size_t size);
    const Info& info() const { return initial; }
    Audio decode(const uint8_t* packet, size_t size);
    void reset() { current = initial; }
private:
    Info initial{}, current{};
};
