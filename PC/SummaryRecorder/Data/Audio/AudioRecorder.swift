import AVFoundation
import Combine

enum RecorderState: String, Sendable {
    case idle
    case recording
    case paused
}

@MainActor
final class AudioRecorder: AudioRecorderProtocol {
    @Published private(set) var state: RecorderState = .idle
    @Published private(set) var recordingDuration: TimeInterval = 0
    @Published private(set) var audioLevel: Double = 0.0

    private var engine: AVAudioEngine?
    private var fileHandle: FileHandle?
    private var outputFileURL: URL?
    private var timer: Timer?
    private var startTime: Date?

    func isRecording() async -> Bool { state == .recording }
    func audioLevel() async -> Double { audioLevel }

    func start(outputDirectory: URL) async throws -> URL {
        guard state == .idle else {
            throw RecorderError.alreadyRecording
        }

        let permission = AVCaptureDevice.authorizationStatus(for: .audio)
        guard permission == .authorized else {
            throw RecorderError.permissionDenied
        }

        let sessionId = UUID().uuidString
        let url = outputDirectory.appendingPathComponent("\(sessionId).wav")
        outputFileURL = url

        // Create WAV file with placeholder header (44 bytes zero-filled)
        FileManager.default.createFile(atPath: url.path, contents: Data(count: 44))
        guard let handle = try? FileHandle(forWritingTo: url) else {
            throw RecorderError.fileCreationFailed
        }
        fileHandle = handle
        handle.seekToEndOfFile()

        // Setup AVAudioEngine: hardware format tap + manual 16kHz/mono conversion
        let engine = AVAudioEngine()
        self.engine = engine

        let hardwareFormat = engine.inputNode.outputFormat(forBus: 0)

        engine.inputNode.installTap(onBus: 0, bufferSize: 4096, format: nil) { [weak self] buffer, _ in
            guard let self, self.state == .recording else { return }

            let srcRate = hardwareFormat.sampleRate
            let srcChannels = Int(hardwareFormat.channelCount)
            let srcLength = Int(buffer.frameLength)
            guard srcLength > 0, srcChannels > 0,
                  let srcData = buffer.floatChannelData else { return }

            // Calculate output length (16kHz resampling)
            let outRate: Double = 16000
            let outLength = Int(Double(srcLength) * outRate / srcRate)
            guard outLength > 0 else { return }

            // Allocate temporary output buffer
            var pcmData = Data(capacity: outLength * 2)

            for outIdx in 0..<outLength {
                // Map to source sample position
                let srcIdx = Double(outIdx) * srcRate / outRate
                let i0 = Int(srcIdx)
                let i1 = min(i0 + 1, srcLength - 1)
                let frac = Float(srcIdx - Double(i0))

                // Average channels → mono, linear interpolate
                var sample: Float = 0
                for ch in 0..<srcChannels {
                    let v0 = srcData[ch][i0]
                    let v1 = srcData[ch][i1]
                    sample += v0 + (v1 - v0) * frac
                }
                sample /= Float(srcChannels)

                // Clamp & convert to Int16 LE
                let clamped = max(-1.0, min(1.0, sample))
                let int16 = Int16(clamped * 32767)
                withUnsafeBytes(of: int16.littleEndian) { pcmData.append(contentsOf: $0) }
            }

            // Write to file
            DispatchQueue.main.async {
                self.fileHandle?.write(pcmData)
            }

            // RMS audio level
            var sum: Float = 0
            for outIdx in 0..<outLength {
                let srcIdx = Double(outIdx) * srcRate / outRate
                let i0 = Int(srcIdx)
                let i1 = min(i0 + 1, srcLength - 1)
                let frac = Float(srcIdx - Double(i0))
                var sample: Float = 0
                for ch in 0..<srcChannels {
                    let v0 = srcData[ch][i0]
                    let v1 = srcData[ch][i1]
                    sample += v0 + (v1 - v0) * frac
                }
                sample /= Float(srcChannels)
                sum += sample * sample
            }
            let rms = sqrt(sum / Float(outLength))
            DispatchQueue.main.async {
                self.audioLevel = Double(min(1.0, rms * 5))
            }
        }

        engine.prepare()
        try engine.start()

        state = .recording
        startTime = Date()

        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self, let start = self.startTime else { return }
            self.recordingDuration = Date().timeIntervalSince(start)
        }

        return url
    }

    func pause() async {
        guard state == .recording else { return }
        state = .paused
        engine?.pause()
        timer?.invalidate()
        timer = nil
    }

    func resume() async {
        guard state == .paused else { return }
        state = .recording
        try? engine?.start()
        startTime = Date().addingTimeInterval(-recordingDuration)
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self, let start = self.startTime else { return }
            self.recordingDuration = Date().timeIntervalSince(start)
        }
    }

    func stop() async throws -> URL {
        guard state != .idle else {
            throw RecorderError.notRecording
        }

        engine?.stop()
        engine?.inputNode.removeTap(onBus: 0)
        timer?.invalidate()
        timer = nil

        guard let url = outputFileURL else {
            throw RecorderError.noOutputFile
        }

        // Finalize WAV header with correct sizes
        try WavWriter.finalizeHeader(url: url)

        // Reset state
        engine = nil
        fileHandle = nil
        outputFileURL = nil
        startTime = nil
        recordingDuration = 0
        audioLevel = 0.0
        state = .idle

        return url
    }
}

// MARK: - Errors

enum RecorderError: Error, LocalizedError {
    case alreadyRecording
    case notRecording
    case permissionDenied
    case fileCreationFailed
    case noOutputFile
    case invalidState

    var errorDescription: String? {
        switch self {
        case .alreadyRecording: "Already recording"
        case .notRecording: "Not currently recording"
        case .permissionDenied: "Microphone permission denied"
        case .fileCreationFailed: "Failed to create output file"
        case .noOutputFile: "No output file available"
        case .invalidState: "Invalid recorder state"
        }
    }
}

// MARK: - WAV Header Finalizer

enum WavWriter {
    static func finalizeHeader(url: URL) throws {
        let handle = try FileHandle(forUpdating: url)
        defer { try? handle.close() }

        let fileSize = try handle.seekToEnd()
        guard fileSize >= 44 else { return }

        let dataLength = UInt32(fileSize - 44)
        let riffChunkSize = UInt32(fileSize - 8)

        handle.seek(toFileOffset: 4)
        var riff = riffChunkSize.littleEndian
        handle.write(Data(bytes: &riff, count: 4))

        handle.seek(toFileOffset: 40)
        var data = dataLength.littleEndian
        handle.write(Data(bytes: &data, count: 4))
    }
}
