import Foundation

/// A single news article returned by the Yahoo Finance search endpoint.
struct NewsItem: Codable, Identifiable, Hashable {
    /// Headline text of the article.
    let title: String

    /// Name of the publishing outlet (e.g. "Reuters").
    let publisher: String

    /// Full article URL that can be opened in a browser.
    let url: String

    /// Unix timestamp in seconds when the article was published.
    let publishedAt: TimeInterval

    var id: String { url }

    /// Publication date as a `Date` for display formatting.
    var publishedDate: Date {
        Date(timeIntervalSince1970: publishedAt)
    }
}
