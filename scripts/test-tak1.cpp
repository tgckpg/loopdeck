// Host-only TAK 1.0 reference comparison helper. Writes original-depth PCM.
// Usage: test-tak1 input.tak output.pcm [maximum_packets]
#include "tak1.h"
#include <iostream>
#include <fstream>
#include <stdexcept>
extern "C" {
#include <libavformat/avformat.h>
}
int main(int argc, char** argv) {
    if (argc < 3) return 2;
    AVFormatContext* format=nullptr;
    AVPacket* packet=av_packet_alloc();
    int count=0;
    try {
        if (avformat_open_input(&format,argv[1],nullptr,nullptr)<0) throw std::runtime_error("Cannot open fixture");
        auto* par=format->streams[0]->codecpar;
        Tak1Decoder decoder(par->extradata,par->extradata_size);
        std::ofstream out(argv[2],std::ios::binary);
        if (!out) throw std::runtime_error("Cannot write PCM output");
        const int bytes=(decoder.info().bits+7)/8;
        int maximum=argc>3 ? std::stoi(argv[3]) : 1000000;
        while (count<maximum && av_read_frame(format,packet)>=0) {
            if (packet->stream_index==0) {
                auto audio=decoder.decode(packet->data,packet->size);
                for (int i=0; i<audio.samples; ++i) for (int c=0; c<decoder.info().channels; ++c) {
                    uint32_t value=uint32_t(audio.channel[c][i]);
                    if (decoder.info().bits==8) value+=128;
                    for (int b=0; b<bytes; ++b) out.put(char(value>>(8*b)));
                }
                ++count;
            }
            av_packet_unref(packet);
        }
        std::cout<<count<<" packets decoded\n";
        av_packet_free(&packet); avformat_close_input(&format); return 0;
    } catch (const std::exception& e) {
        std::cerr<<"After "<<count<<" packets: "<<e.what()<<"\n";
        av_packet_free(&packet); avformat_close_input(&format); return 1;
    }
}
