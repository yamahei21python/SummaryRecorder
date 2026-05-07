import Foundation

enum TestWavFactory {
    static func createWav(durationSeconds: Int, sampleRate: Int = 16000,
                          channels: Int = 1) -> Data {
        let bytesPerSample = 2
        let numSamples = sampleRate * durationSeconds * channels
        let dataSize = numSamples * bytesPerSample
        let headerSize = 44
        let fileSize = headerSize + dataSize

        var data = Data(capacity: fileSize)
        // RIFF header
        data.append(contentsOf: [UInt8]("RIFF".utf8))
        appendInt32(&data, value: Int32(fileSize - 8))
        data.append(contentsOf: [UInt8]("WAVE".utf8))
        // fmt chunk
        data.append(contentsOf: [UInt8]("fmt ".utf8))
        appendInt32(&data, value: 16)
        appendInt16(&data, value: 1)
        appendInt16(&data, value: Int16(channels))
        appendInt32(&data, value: Int32(sampleRate))
        appendInt32(&data, value: Int32(sampleRate * channels * bytesPerSample))
        appendInt16(&data, value: Int16(channels * bytesPerSample))
        appendInt16(&data, value: Int16(bytesPerSample * 8))
        // data chunk
        data.append(contentsOf: [UInt8]("data".utf8))
        appendInt32(&data, value: Int32(dataSize))
        // silence
        data.append(Data(count: dataSize))
        return data
    }

    static func createCorruptWav_zeroDataLength() -> Data {
        var data = TestWavFactory.createWav(durationSeconds: 1)
        data.replaceSubrange(40..<44, with: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
        return data
    }

    static func createCorruptWav_badRiffSize() -> Data {
        var data = TestWavFactory.createWav(durationSeconds: 1)
        data.replaceSubrange(4..<8, with: withUnsafeBytes(of: Int32(999999).littleEndian) { Data($0) })
        return data
    }

    static func createCorruptWav_truncated() -> Data {
        Data([0x52, 0x49, 0x46, 0x46])
    }

    static func createLargeWav(sizeMB: Int) -> URL {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        try! FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let url = tempDir.appendingPathComponent("large.wav")
        let dataSize = sizeMB * 1024 * 1024 - 44
        var wavData = createWav(durationSeconds: 1)
        wavData.replaceSubrange(40..<44, with: withUnsafeBytes(of: Int32(dataSize).littleEndian) { Data($0) })
        wavData.replaceSubrange(4..<8, with: withUnsafeBytes(of: Int32(dataSize + 36).littleEndian) { Data($0) })
        wavData.append(Data(count: dataSize))
        try! wavData.write(to: url)
        return url
    }

    private static func appendInt32(_ data: inout Data, value: Int32) {
        var v = value.littleEndian
        data.append(Data(bytes: &v, count: 4))
    }

    private static func appendInt16(_ data: inout Data, value: Int16) {
        var v = value.littleEndian
        data.append(Data(bytes: &v, count: 2))
    }
}
