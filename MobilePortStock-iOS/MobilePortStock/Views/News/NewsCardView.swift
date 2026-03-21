import SwiftUI

/// A single news article row.
struct NewsCardView: View {
    let item: NewsItem

    var body: some View {
        if let url = URL(string: item.url) {
            Link(destination: url) {
                content
            }
        } else {
            content
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(item.title)
                .font(.subheadline)
                .lineLimit(3)

            HStack {
                Text(item.publisher)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(item.publishedDate, style: .relative)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
    }
}
