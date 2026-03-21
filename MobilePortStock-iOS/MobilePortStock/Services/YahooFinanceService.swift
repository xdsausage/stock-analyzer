import Foundation

/// Errors thrown by ``YahooFinanceService``.
enum YahooFinanceError: LocalizedError {
    case invalidTicker(String)
    case exchangeNotSupported(String)
    case networkFailure(Int)
    case sessionExpired
    case parseFailure(String)

    var errorDescription: String? {
        switch self {
        case .invalidTicker(let t):
            "Ticker \"\(t)\" not found or not available."
        case .exchangeNotSupported(let e):
            "Exchange \"\(e)\" is not NASDAQ or NYSE."
        case .networkFailure(let code):
            "HTTP \(code) — request failed."
        case .sessionExpired:
            "Yahoo Finance session expired. Please try again."
        case .parseFailure(let msg):
            "Parse error: \(msg)"
        }
    }
}

/// Async actor that fetches stock data from the unofficial Yahoo Finance API.
///
/// Uses a crumb-based authentication flow identical to the Java
/// `YahooFinanceFetcher`: visit the homepage to set cookies, then exchange
/// them for a crumb token appended to every API request.
actor YahooFinanceService {

    // MARK: - Constants

    private static let browserUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private static let yahooHomepage   = "https://finance.yahoo.com/"
    private static let crumbEndpoint   = "https://query1.finance.yahoo.com/v1/test/getcrumb"

    private static let quoteSummaryURL = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%@?modules=price,summaryDetail,defaultKeyStatistics&crumb=%@"
    private static let chartURL        = "https://query1.finance.yahoo.com/v8/finance/chart/%@?interval=%@&range=%@&crumb=%@"
    private static let newsSearchURL   = "https://query1.finance.yahoo.com/v1/finance/search?q=%@&newsCount=%d&quotesCount=0"
    private static let screenerURL     = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved?formatted=false&count=%d&scrIds=%@"
    private static let optionsURL      = "https://query2.finance.yahoo.com/v7/finance/options/%@?crumb=%@"
    private static let optionsDateURL  = "https://query2.finance.yahoo.com/v7/finance/options/%@?date=%d&crumb=%@"
    private static let earningsURL     = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%@?modules=calendarEvents,price&crumb=%@"
    private static let dividendURL     = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%@?modules=calendarEvents,summaryDetail,price&crumb=%@"
    private static let sectorInfoURL   = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%@?modules=assetProfile&crumb=%@"

    // MARK: - State

    private var sessionCrumb: String?
    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.httpCookieStorage = HTTPCookieStorage.shared
        config.timeoutIntervalForRequest = 15
        self.session = URLSession(configuration: config)
    }

    // MARK: - Crumb management

    private func ensureCrumb() async throws -> String {
        if let crumb = sessionCrumb { return crumb }
        return try await refreshCrumb()
    }

    private func refreshCrumb() async throws -> String {
        // Step 1: Visit homepage to set session cookies
        var homeReq = URLRequest(url: URL(string: Self.yahooHomepage)!)
        homeReq.setValue(Self.browserUserAgent, forHTTPHeaderField: "User-Agent")
        homeReq.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        _ = try await session.data(for: homeReq)

        // Step 2: Exchange cookies for crumb
        var crumbReq = URLRequest(url: URL(string: Self.crumbEndpoint)!)
        crumbReq.setValue(Self.browserUserAgent, forHTTPHeaderField: "User-Agent")
        crumbReq.setValue("*/*", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: crumbReq)

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200,
              let crumb = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !crumb.isEmpty else {
            throw YahooFinanceError.sessionExpired
        }

        sessionCrumb = crumb
        return crumb
    }

    /// Makes a GET request with browser-like headers. Returns (data, statusCode).
    private func get(_ urlString: String) async throws -> (Data, Int) {
        guard let url = URL(string: urlString) else {
            throw YahooFinanceError.parseFailure("Invalid URL: \(urlString)")
        }
        var request = URLRequest(url: url)
        request.setValue(Self.browserUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (data, statusCode)
    }

    /// Makes a request with automatic crumb retry on 401.
    private func getWithCrumb(_ urlTemplate: String, _ args: Any...) async throws -> Data {
        var crumb = try await ensureCrumb()
        var allArgs = args + [crumb]
        var urlString = String(format: urlTemplate, arguments: allArgs.map { $0 as! CVarArg })
        var (data, statusCode) = try await get(urlString)

        if statusCode == 401 {
            crumb = try await refreshCrumb()
            allArgs = args + [crumb]
            urlString = String(format: urlTemplate, arguments: allArgs.map { $0 as! CVarArg })
            (data, statusCode) = try await get(urlString)
        }

        guard statusCode == 200 else {
            throw YahooFinanceError.networkFailure(statusCode)
        }
        return data
    }

    // MARK: - Public API

    /// Fetches comprehensive stock data for the given ticker.
    func fetch(_ ticker: String) async throws -> StockData {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !upper.isEmpty else {
            throw YahooFinanceError.invalidTicker(ticker)
        }

        let data = try await getWithCrumb(Self.quoteSummaryURL, upper as NSString)
        return try YahooFinanceParser.parseStockData(ticker: upper, data: data)
    }

    /// Fetches OHLC chart bars for the given ticker and interval.
    func fetchChart(_ ticker: String, interval: ChartInterval) async throws -> ChartData {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        let data = try await getWithCrumb(Self.chartURL, upper as NSString,
                                          interval.yahooInterval as NSString,
                                          interval.yahooRange as NSString)
        return try YahooFinanceParser.parseChartData(data: data)
    }

    /// Fetches chart data with explicit interval and range strings.
    func fetchChart(_ ticker: String, barInterval: String, timeRange: String) async throws -> ChartData {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        let data = try await getWithCrumb(Self.chartURL, upper as NSString,
                                          barInterval as NSString,
                                          timeRange as NSString)
        return try YahooFinanceParser.parseChartData(data: data)
    }

    /// Fetches news articles for the given query.
    func fetchNews(_ query: String, maxItems: Int = 8) async -> [NewsItem] {
        do {
            let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
            let urlString = String(format: Self.newsSearchURL, encoded, max(1, maxItems))
            let (data, statusCode) = try await get(urlString)
            guard statusCode == 200 else { return [] }
            return YahooFinanceParser.parseNewsItems(data: data)
        } catch {
            return []
        }
    }

    /// Fetches top market news of the day.
    func fetchTopNewsOfDay() async -> [NewsItem] {
        let items = await fetchNews("stock market", maxItems: 12)
        guard !items.isEmpty else { return items }

        let todayStart = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970
        let todayItems = items.filter { $0.publishedAt >= todayStart }

        if todayItems.isEmpty {
            return Array(items.prefix(5))
        }
        return Array(todayItems.prefix(5))
    }

    /// Fetches screener results for a predefined screen ID.
    func fetchScreener(_ screenId: String, count: Int = 25) async -> [ScreenerStock] {
        do {
            _ = try await ensureCrumb()
            let encoded = screenId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? screenId
            let urlString = String(format: Self.screenerURL, max(1, count), encoded)
            var (data, statusCode) = try await get(urlString)
            if statusCode == 401 {
                _ = try await refreshCrumb()
                (data, statusCode) = try await get(urlString)
            }
            guard statusCode == 200 else { return [] }
            return YahooFinanceParser.parseScreenerStocks(data: data)
        } catch {
            return []
        }
    }

    /// Fetches options chain for the nearest expiration.
    func fetchOptions(_ ticker: String) async throws -> OptionsChain {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        let data = try await getWithCrumb(Self.optionsURL, upper as NSString)
        return try YahooFinanceParser.parseOptionsChain(ticker: upper, data: data)
    }

    /// Fetches options chain for a specific expiration date.
    func fetchOptions(_ ticker: String, expiration: TimeInterval) async throws -> OptionsChain {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        let data = try await getWithCrumb(Self.optionsDateURL, upper as NSString, Int(expiration))
        return try YahooFinanceParser.parseOptionsChain(ticker: upper, data: data)
    }

    /// Fetches earnings calendar entries for the given tickers.
    func fetchEarningsCalendar(_ tickers: [String]) async -> [EarningsEntry] {
        var entries: [EarningsEntry] = []
        for ticker in tickers {
            do {
                let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
                let data = try await getWithCrumb(Self.earningsURL, upper as NSString)
                if let entry = YahooFinanceParser.parseEarningsEntry(ticker: upper, data: data) {
                    entries.append(entry)
                }
            } catch { /* skip ticker */ }
        }
        return entries
    }

    /// Fetches dividend calendar entries for the given tickers.
    func fetchDividendCalendar(_ tickers: [String]) async -> [DividendEntry] {
        var entries: [DividendEntry] = []
        for ticker in tickers {
            do {
                let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
                let data = try await getWithCrumb(Self.dividendURL, upper as NSString)
                if let entry = YahooFinanceParser.parseDividendEntry(ticker: upper, data: data) {
                    entries.append(entry)
                }
            } catch { /* skip ticker */ }
        }
        return entries
    }

    /// Sector info for a ticker.
    struct SectorInfo {
        let sector: String
        let industry: String
    }

    func fetchSectorInfo(_ ticker: String) async -> SectorInfo {
        do {
            let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
            let data = try await getWithCrumb(Self.sectorInfoURL, upper as NSString)
            return YahooFinanceParser.parseSectorInfo(data: data)
        } catch {
            return SectorInfo(sector: "Unknown", industry: "Unknown")
        }
    }
}
