import SwiftUI

enum ProcessingPhase {
    case recording
    case transcribing
    case summarizing
}

struct RecordingOverlayView: View {
    @ObservedObject var viewModel: MainViewModel
    let onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(phase == .recording ? 0.45 : 0.55)
                .ignoresSafeArea()
                .onTapGesture { onDismiss() }

            VStack(spacing: 28) {
                Spacer()

                switch phase {
                case .recording:
                    recordingContent
                case .transcribing:
                    processingContent(
                        icon: "waveform",
                        title: "文字起こし中",
                        color: .orange
                    )
                case .summarizing:
                    processingContent(
                        icon: "text.bubble",
                        title: "要約中",
                        color: .blue
                    )
                }

                Text("クリックで閉じる")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.5))

                Spacer()
            }
        }
        .transition(.opacity.animation(.easeInOut(duration: 0.25)))
    }

    private var phase: ProcessingPhase {
        if viewModel.isRecording || viewModel.isPaused {
            return .recording
        } else if viewModel.isTranscribing {
            return .transcribing
        } else if viewModel.isSummarizing {
            return .summarizing
        }
        return .recording // fallback
    }

    // MARK: - Recording

    private var recordingContent: some View {
        VStack(spacing: 28) {
            // ● REC 00:32
            HStack(spacing: 14) {
                PulsingDot()
                    .frame(width: 18, height: 18)
                Text("REC")
                    .font(.system(size: 40, weight: .heavy))
                    .foregroundStyle(.white)
                Text(formatDuration(viewModel.recordingDuration))
                    .font(.system(size: 40, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                    .monospacedDigit()
            }

            RecordingWaveformView(level: viewModel.audioLevel, isPaused: viewModel.isPaused)
                .frame(height: 100)
                .padding(.horizontal, 60)

            HStack(spacing: 40) {
                Button(action: {
                    Task {
                        if viewModel.isRecording {
                            await viewModel.pauseRecording()
                        } else if viewModel.isPaused {
                            await viewModel.resumeRecording()
                        }
                    }
                }) {
                    Image(systemName: viewModel.isPaused ? "play.fill" : "pause.fill")
                        .font(.system(size: 36))
                        .foregroundStyle(.white)
                        .frame(width: 72, height: 72)
                        .background(.white.opacity(0.15), in: Circle())
                }
                .buttonStyle(.plain)

                Button(action: {
                    Task { await viewModel.stopRecording() }
                }) {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 36))
                        .foregroundStyle(.white)
                        .frame(width: 72, height: 72)
                        .background(.red, in: Circle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Processing (Transcribing / Summarizing)

    private func processingContent(icon: String, title: String, color: Color) -> some View {
        VStack(spacing: 24) {
            Image(systemName: icon)
                .font(.system(size: 64))
                .foregroundStyle(color)

            Text(title)
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(.white)

            ProgressView()
                .progressViewStyle(.circular)
                .tint(.white)
                .scaleEffect(1.5)
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let m = Int(duration) / 60
        let s = Int(duration) % 60
        return String(format: "%02d:%02d", m, s)
    }
}

// MARK: - パルス赤点

struct PulsingDot: View {
    @State private var isPulsing = false

    var body: some View {
        Circle()
            .fill(.red)
            .scaleEffect(isPulsing ? 1.4 : 0.8)
            .opacity(isPulsing ? 0.5 : 1.0)
            .animation(
                .easeInOut(duration: 0.7).repeatForever(autoreverses: true),
                value: isPulsing
            )
            .onAppear { isPulsing = true }
    }
}

// MARK: - 波形レベルメーター

struct RecordingWaveformView: View {
    let level: Double
    let isPaused: Bool

    private let barCount = 32

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 4) {
                ForEach(0..<barCount, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 3)
                        .fill(barColor(i))
                        .frame(width: barWidth(geo), height: barHeight(i, geo))
                        .animation(.spring(response: 0.15), value: level)
                }
            }
            .frame(maxHeight: .infinity)
        }
    }

    private func barWidth(_ geo: GeometryProxy) -> CGFloat {
        let total = geo.size.width - CGFloat(barCount - 1) * 4
        return max(2, total / CGFloat(barCount))
    }

    private func barHeight(_ i: Int, _ geo: GeometryProxy) -> CGFloat {
        guard !isPaused else { return 4 }
        let normalized = Double(i) / Double(barCount)
        let curve = sin(normalized * .pi)
        let active = level * curve * 0.9 + 0.1
        return max(4, geo.size.height * CGFloat(active))
    }

    private func barColor(_ i: Int) -> Color {
        let normalized = Double(i) / Double(barCount)
        if normalized > 0.8 { return .red.opacity(0.8) }
        if normalized > 0.6 { return .orange.opacity(0.8) }
        return .green.opacity(0.8)
    }
}
