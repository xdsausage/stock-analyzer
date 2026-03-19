/**
 * Immutable data record for a single news article returned by the Yahoo Finance
 * search endpoint.
 *
 * @param title       headline text of the article
 * @param publisher   name of the publishing outlet (e.g. "Reuters")
 * @param url         full article URL that can be opened in a browser
 * @param publishedAt Unix timestamp in seconds when the article was published
 */
public record NewsItem(String title, String publisher, String url, long publishedAt) {}
