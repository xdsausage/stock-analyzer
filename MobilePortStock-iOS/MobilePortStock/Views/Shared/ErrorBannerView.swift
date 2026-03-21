import SwiftUI

/// A dismissible error banner.
struct ErrorBannerView: View {
    let message: String
    var onDismiss: (() -> Void)?

    var body: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.yellow)
            Text(message)
                .font(.subheadline)
            Spacer()
            if let onDismiss {
                Button("Dismiss", action: onDismiss)
                    .font(.subheadline.bold())
            }
        }
        .padding()
        .background(Color.red.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
    }
}
