import Foundation

enum WavError: Error, Sendable {
    case invalidFormat
    case fileTooSmall
    case invalidRiffHeader
    case invalidFmtChunk
    case missingDataChunk
}

struct WavHeader: Sendable {
    let sampleRate: Int
    let channels: Int
    let bitsPerSample: Int
    let dataLength: Int64
    let riffChunkSize: Int64

    init(url: URL) throws {
        let data = try Data(contentsOf: url)
        try self.init(data: data)
    }

    init(data: Data) throws {
        guard data.count >= 44 else {
            throw WavError.fileTooSmall
        }

        // RIFF header validation (offset 0-11)
        guard let riff = String(data: data[0..<4], encoding: .ascii),
              riff == "RIFF" else {
            throw WavError.invalidRiffHeader
        }
        guard let wave = String(data: data[8..<12], encoding: .ascii),
              wave == "WAVE" else {
            throw WavError.invalidFormat
        }

        riffChunkSize = Int64(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 4, as: UInt32.self) })

        // fmt chunk (offset 12-35)
        guard let fmtId = String(data: data[12..<16], encoding: .ascii),
              fmtId == "fmt " else {
            throw WavError.invalidFmtChunk
        }

        // PCM format check
        let audioFormat = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 20, as: UInt16.self) }
        guard audioFormat == 1 else {
            throw WavError.invalidFormat
        }

        channels = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 22, as: UInt16.self) })
        sampleRate = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 24, as: UInt32.self) })
        bitsPerSample = Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 34, as: UInt16.self) })

        // data chunk (offset 36-43)
        guard let dataId = String(data: data[36..<40], encoding: .ascii),
              dataId == "data" else {
            throw WavError.missingDataChunk
        }

        dataLength = Int64(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 40, as: UInt32.self) })
    }

    init(sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16,
         dataLength: Int64 = 0, riffChunkSize: Int64 = 0) {
        self.sampleRate = sampleRate
        self.channels = channels
        self.bitsPerSample = bitsPerSample
        self.dataLength = dataLength
        self.riffChunkSize = riffChunkSize
    }
}
