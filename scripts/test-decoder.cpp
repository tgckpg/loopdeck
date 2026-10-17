#include "decoder.h"
#include <algorithm>
#include <cmath>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <unistd.h>
#include <vector>

// Host-only integration test: run against the same FFmpeg release as the app.
// Compares seeks with sequential decoding, including decoder/resampler draining.
int main(int argc,char** argv) {
    if (argc<2) return 2;
    try {
        for (int i=1;i<argc;i++) {
            int fd=::open(argv[i],O_RDONLY);
            if (fd<0) throw std::runtime_error("Cannot open fixture");
            Decoder d(fd); ::close(fd); d.open();
            for (int rate : {d.sourceRate(),48000}) {
                d.outputRate(rate); d.seek(0);
                std::vector<float> all, chunk(2048*2);
                int count;
                while ((count=d.read(chunk.data(),2048))>0) all.insert(all.end(),chunk.begin(),chunk.begin()+count*2);
                if (all.size()<static_cast<size_t>(rate*4)) throw std::runtime_error("Fixture must contain at least 2 seconds");
                if (d.read(chunk.data(),2048)!=0) throw std::runtime_error("EOF was not stable");
                for (int64_t target : {int64_t(0),int64_t(rate/3),int64_t(rate),int64_t(rate+137),int64_t(rate)*3+137}) {
                    d.seek(target);
                    count=d.read(chunk.data(),1024);
                    if (count!=1024) throw std::runtime_error("Short seek output");
                    double maximum=0;
                    for (int j=0;j<count*2;j++) maximum=std::max(maximum,std::abs(double(chunk[j]-all[target*2+j])));
                    // Resampling starts a new FIR filter at a seek. Ignore filter warm-up
                    // only for the first 64 frames when evaluating resampled output.
                    if (rate!=d.sourceRate()) {
                        maximum=0;
                        for (int j=128;j<count*2;j++) maximum=std::max(maximum,std::abs(double(chunk[j]-all[target*2+j])));
                    }
                    if (maximum>0.003) throw std::runtime_error(std::string(argv[i])+": seek mismatch "+std::to_string(maximum)+" at "+std::to_string(target)+" rate "+std::to_string(rate));
                }
                std::cout<<argv[i]<<" @ "<<rate<<": sequential decode, EOF, seeks passed ("<<all.size()/2<<" frames)\n";
            }
        }
    } catch (const std::exception& e) { std::cerr<<e.what()<<"\n"; return 1; }
}
