#import "WhisperBridge.h"
#import "whisper.h"

#include <string>
#include <vector>
#include <atomic>
#include <fstream>
#include <cstdint>

// Simple WAV reader: returns mono 16kHz float samples
static bool read_wav_file(const char *path, std::vector<float> &samples) {
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;

    // Read WAV header
    char riff[4];
    file.read(riff, 4);
    if (std::string(riff, 4) != "RIFF") return false;

    uint32_t fileSize;
    file.read((char *)&fileSize, 4);

    char wave[4];
    file.read(wave, 4);
    if (std::string(wave, 4) != "WAVE") return false;

    // Find fmt chunk
    uint16_t audioFormat = 0, numChannels = 0, bitsPerSample = 0;
    uint32_t sampleRate = 0, dataChunkSize = 0;
    bool foundFmt = false, foundData = false;

    while (file && !(foundFmt && foundData)) {
        char chunkId[4];
        file.read(chunkId, 4);
        uint32_t chunkSize;
        file.read((char *)&chunkSize, 4);

        if (std::string(chunkId, 4) == "fmt ") {
            file.read((char *)&audioFormat, 2);
            file.read((char *)&numChannels, 2);
            file.read((char *)&sampleRate, 4);
            file.seekg(4, std::ios::cur); // byteRate
            file.seekg(2, std::ios::cur); // blockAlign
            file.read((char *)&bitsPerSample, 2);
            foundFmt = true;
            // Skip extra fmt bytes
            if (chunkSize > 16) file.seekg(chunkSize - 16, std::ios::cur);
        } else if (std::string(chunkId, 4) == "data") {
            dataChunkSize = chunkSize;
            foundData = true;
        } else {
            file.seekg(chunkSize, std::ios::cur);
        }
    }

    if (!foundFmt || !foundData) return false;
    if (audioFormat != 1) return false; // PCM only

    const uint32_t numSamples = dataChunkSize / (numChannels * (bitsPerSample / 8));

    // Read samples and convert to mono float
    std::vector<float> raw(numSamples);
    if (bitsPerSample == 16) {
        std::vector<int16_t> pcm(numSamples * numChannels);
        file.read((char *)pcm.data(), pcm.size() * sizeof(int16_t));
        for (uint32_t i = 0; i < numSamples; i++) {
            float sum = 0;
            for (uint16_t c = 0; c < numChannels; c++) {
                sum += pcm[i * numChannels + c];
            }
            raw[i] = sum / numChannels / 32768.0f;
        }
    } else if (bitsPerSample == 32 && audioFormat == 3) {
        // 32-bit float
        std::vector<float> pcm(numSamples * numChannels);
        file.read((char *)pcm.data(), pcm.size() * sizeof(float));
        for (uint32_t i = 0; i < numSamples; i++) {
            float sum = 0;
            for (uint16_t c = 0; c < numChannels; c++) {
                sum += pcm[i * numChannels + c];
            }
            raw[i] = sum / numChannels;
        }
    } else {
        return false;
    }

    // Resample to 16kHz if needed
    if (sampleRate == 16000) {
        samples = std::move(raw);
    } else {
        const double ratio = 16000.0 / sampleRate;
        samples.resize((uint32_t)(raw.size() * ratio));
        for (uint32_t i = 0; i < samples.size(); i++) {
            const double srcIdx = i / ratio;
            const uint32_t idx0 = (uint32_t)srcIdx;
            const uint32_t idx1 = std::min(idx0 + 1, (uint32_t)(raw.size() - 1));
            const float frac = (float)(srcIdx - idx0);
            samples[i] = raw[idx0] * (1.0f - frac) + raw[idx1] * frac;
        }
    }

    return true;
}

@implementation WhisperBridgeObjC {
    struct whisper_context *_ctx;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error {
    self = [super init];
    if (self) {
        _ctx = nullptr;
        struct whisper_context_params cparams = whisper_context_default_params();
        _ctx = whisper_init_from_file_with_params([modelPath UTF8String], cparams);
        if (!_ctx) {
            if (error) {
                *error = [NSError errorWithDomain:@"WhisperBridge"
                                            code:1
                                        userInfo:@{NSLocalizedDescriptionKey: @"Failed to load whisper model"}];
            }
            return nil;
        }
    }
    return self;
}

- (void)dealloc {
    if (_ctx) {
        whisper_free(_ctx);
        _ctx = nullptr;
    }
}

- (void)transcribeWav:(NSString *)wavPath
             completion:(void (^)(NSString * _Nullable, NSError * _Nullable))completion {
    if (!_ctx) {
        completion(nil, [NSError errorWithDomain:@"WhisperBridge" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: @"Model not loaded"}]);
        return;
    }

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        // Capture context locally to avoid implicit retain of self
        struct whisper_context *ctx = _ctx;
        if (!ctx) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"WhisperBridge" code:2
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Model not loaded"}]);
            });
            return;
        }

        // Read WAV file into float samples
        std::vector<float> samples;
        if (!read_wav_file([wavPath UTF8String], samples) || samples.empty()) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"WhisperBridge" code:4
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Failed to read WAV file"}]);
            });
            return;
        }

        struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wparams.print_progress = false;
        wparams.print_realtime = false;
        wparams.print_timestamps = false;
        wparams.language = "auto";
        wparams.translate = false;
        wparams.n_threads = (int)[[NSProcessInfo processInfo] processorCount];
        wparams.suppress_blank = true;
        wparams.suppress_nst = true;

        // whisper_full returns 0 on success
        int rc = whisper_full(ctx, wparams, samples.data(), (int)samples.size());
        if (rc == 0) {
            std::string result;
            const int n = whisper_full_n_segments(ctx);
            for (int i = 0; i < n; i++) {
                const char *text = whisper_full_get_segment_text(ctx, i);
                if (text && text[0] != '\0') {
                    if (!result.empty()) result += " ";
                    result += text;
                }
            }
            NSString *nsResult = result.empty() ? nil : @(result.c_str());
            dispatch_async(dispatch_get_main_queue(), ^{ completion(nsResult, nil); });
        } else {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"WhisperBridge" code:3
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Transcription failed"}]);
            });
        }
    });
}

- (void)cancel {
    // whisper.cpp doesn't have an abort API in this version
    // Background thread will complete on its own for short audio
}

@end
