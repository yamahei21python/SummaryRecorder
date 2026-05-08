import SwiftUI

struct SummarizingOverlayView: View {
    @ObservedObject var viewModel: MainViewModel
    let onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.4)
                .ignoresSafeArea()
                .onTapGesture { onDismiss() }

            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "text.bubble")
                    .font(.system(size: 56))
                    .foregroundStyle(.blue)

                Text("要約中")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(.white)

                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                    .scaleEffect(1.3)

                Text("クリックで閉じる")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.5))

                Spacer()
            }
        }
        .transition(.opacity.animation(.easeInOut(duration: 0.2)))
    }
}
