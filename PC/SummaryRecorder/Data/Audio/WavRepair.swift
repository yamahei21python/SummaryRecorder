import Foundation

struct WavRepair {
    static let headerSize = 44

    /// 不完全WAV修復
    /// - Returns: true=修復成功, false=修復不可(ユーザー破棄確認要)
    static func repair(url: URL) throws -> Bool {
        let fileHandle = try FileHandle(forReadingFrom: url)
        defer { try? fileHandle.close() }

        let fileSize = try fileHandle.seekToEnd()
        guard fileSize >= Self.headerSize else {
            return false
        }

        // RIFF/WAVE検証
        fileHandle.seek(toFileOffset: 0)
        guard let riffTag = try fileHandle.read(upToCount: 4),
              String(data: riffTag, encoding: .ascii) == "RIFF" else {
            return false
        }

        fileHandle.seek(toFileOffset: 8)
        guard let waveTag = try fileHandle.read(upToCount: 4),
              String(data: waveTag, encoding: .ascii) == "WAVE" else {
            return false
        }

        let actualDataSize = Int(fileSize) - Self.headerSize
        guard actualDataSize > 0 else {
            return false
        }

        let correctDataLength = Int64(actualDataSize)
        let correctRiffChunkSize = Int64(Int(fileSize) - 8)

        // 現在のヘッダー値読取
        let header = try WavHeader(url: url)
        let needsRepair = header.dataLength != correctDataLength || header.riffChunkSize != correctRiffChunkSize

        guard needsRepair else {
            return true
        }

        // ヘッダー書換え (dataLength: offset 40, riffChunkSize: offset 4)
        let writeHandle = try FileHandle(forWritingTo: url)
        defer { try? writeHandle.close() }

        // riffChunkSize (offset 4, 4 bytes, little-endian)
        writeHandle.seek(toFileOffset: 4)
        var riffSize = UInt32(truncatingIfNeeded: correctRiffChunkSize).littleEndian
        writeHandle.write(Data(bytes: &riffSize, count: 4))

        // dataLength (offset 40, 4 bytes, little-endian)
        writeHandle.seek(toFileOffset: 40)
        var dataSize = UInt32(truncatingIfNeeded: correctDataLength).littleEndian
        writeHandle.write(Data(bytes: &dataSize, count: 4))

        return true
    }
}
