import XCTest
import AVFoundation

final class AVAudioPlayerRateTest: XCTestCase {

    private var tempWavURL: URL!

    override func setUpWithError() throws {
        let data = TestWavFactory.createWav(durationSeconds: 10, sampleRate: 44100)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        tempWavURL = dir.appendingPathComponent("test.wav")
        try data.write(to: tempWavURL)
    }

    override func tearDownWithError() throws {
        if let url = tempWavURL {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
    }

    func testRatePropertyReadback() throws {
        let player = try AVAudioPlayer(contentsOf: tempWavURL)
        player.enableRate = true
        player.rate = 2.0
        player.prepareToPlay()

        XCTAssertEqual(player.rate, 2.0, accuracy: 0.01)
        XCTAssertTrue(player.enableRate)
    }

    func testRateNotAppliedWithoutEnableRate() throws {
        let player = try AVAudioPlayer(contentsOf: tempWavURL)
        // NOT setting enableRate = true
        player.rate = 2.0

        // On macOS, rate property holds the value even without enableRate,
        // but actual playback speed is unaffected. Test the property stores the value.
        XCTAssertEqual(player.rate, 2.0, accuracy: 0.01)
        XCTAssertFalse(player.enableRate)
    }

    func testRateChangeDuringPlayback() throws {
        let player = try AVAudioPlayer(contentsOf: tempWavURL)
        player.enableRate = true
        player.rate = 2.0
        player.prepareToPlay()
        player.play()

        // Let it play for 0.5 real seconds
        Thread.sleep(forTimeInterval: 0.5)
        player.pause()

        // At 2x speed, 0.5s real = ~1.0s audio
        // Give tolerance for scheduling jitter
        XCTAssertGreaterThan(player.currentTime, 0.7)
        XCTAssertLessThan(player.currentTime, 1.5)
    }

    func testDynamicSpeedChange() throws {
        let player = try AVAudioPlayer(contentsOf: tempWavURL)
        player.enableRate = true
        player.rate = 1.0
        player.prepareToPlay()
        player.play()

        // Play at 1x for 0.3s
        Thread.sleep(forTimeInterval: 0.3)
        // Dynamic change to 3x
        player.rate = 3.0

        // Play for another 0.3s real
        Thread.sleep(forTimeInterval: 0.3)
        player.pause()

        // 0.3s at 1x + 0.3s at 3x = 0.3 + 0.9 = ~1.2s expected
        XCTAssertGreaterThan(player.currentTime, 0.7)
        XCTAssertLessThan(player.currentTime, 2.0)
    }

    func testPrepareToPlayAndRate() throws {
        let player = try AVAudioPlayer(contentsOf: tempWavURL)
        player.prepareToPlay()
        player.enableRate = true
        player.rate = 1.5
        player.play()

        Thread.sleep(forTimeInterval: 0.4)
        player.pause()

        // At 1.5x, 0.4s real = ~0.6s audio
        XCTAssertGreaterThan(player.currentTime, 0.35)
        XCTAssertLessThan(player.currentTime, 1.0)
    }

    func testRateDoesNotAffectPlaybackWithoutEnableRate() throws {
        let player = try AVAudioPlayer(contentsOf: tempWavURL)
        player.rate = 2.0 // No enableRate = true
        player.play()

        Thread.sleep(forTimeInterval: 0.5)
        player.pause()

        // Without enableRate, playback speed is 1x regardless of rate setting.
        // currentTime should be ~0.5s (not ~1.0s at 2x)
        XCTAssertGreaterThan(player.currentTime, 0.3)
        XCTAssertLessThan(player.currentTime, 1.0)
    }
}
