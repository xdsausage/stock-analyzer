import Foundation

/// JSON parsing for Yahoo Finance API responses.
///
/// Uses `JSONSerialization` with helper extractors to handle Yahoo's nested
/// `{"raw": 173.5, "fmt": "173.50"}` wrapper format.
enum YahooFinanceParser {

    // MARK: - Stock Data

    static func parseStockData(ticker: String, data: Data) throws -> StockData {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let quoteSummary = root["quoteSummary"] as? [String: Any],
              let results = quoteSummary["result"] as? [[String: Any]],
              let result = results.first else {
            throw YahooFinanceError.parseFailure("Could not parse quoteSummary response.")
        }

        let priceModule = result["price"] as? [String: Any] ?? [:]
        let summaryModule = result["summaryDetail"] as? [String: Any] ?? [:]
        let statsModule = result["defaultKeyStatistics"] as? [String: Any] ?? [:]

        // Exchange validation
        let exchangeName = extractString(priceModule, "exchangeName")
            ?? extractString(priceModule, "exchange")
        if let exchange = exchangeName, !isAcceptedUSExchange(exchange) {
            throw YahooFinanceError.exchangeNotSupported(exchange)
        }

        // Company name
        let companyName = extractString(priceModule, "longName")
            ?? extractString(priceModule, "shortName")
            ?? ticker

        let currency = extractString(priceModule, "currency") ?? "USD"

        // Price change percent: API returns as decimal (0.0235), multiply by 100
        let rawChangePercent = extractRawDouble(priceModule, "regularMarketChangePercent") ?? 0
        let changePercent = rawChangePercent * 100.0

        // P/E ratios (try summaryDetail first, fall back to stats)
        let peRatio = extractRawDouble(summaryModule, "trailingPE")
            ?? extractRawDouble(statsModule, "trailingPE")
        let forwardPE = extractRawDouble(summaryModule, "forwardPE")
            ?? extractRawDouble(statsModule, "forwardPE")

        return StockData(
            symbol: ticker,
            companyName: companyName,
            exchange: exchangeName ?? "",
            currency: currency,
            currentPrice: extractRawDouble(priceModule, "regularMarketPrice") ?? 0,
            priceChange: extractRawDouble(priceModule, "regularMarketChange") ?? 0,
            priceChangePercent: changePercent,
            marketCap: extractRawDouble(priceModule, "marketCap") ?? 0,
            tradingVolume: Int(extractRawDouble(priceModule, "regularMarketVolume") ?? 0),
            averageDailyVolume: Int(extractRawDouble(summaryModule, "averageVolume") ?? 0),
            peRatio: peRatio,
            forwardPE: forwardPE,
            earningsPerShare: extractRawDouble(statsModule, "trailingEps"),
            priceToBook: extractRawDouble(statsModule, "priceToBook"),
            beta: extractRawDouble(statsModule, "beta"),
            dividendYield: extractRawDouble(summaryModule, "dividendYield"),
            fiftyTwoWeekHigh: extractRawDouble(summaryModule, "fiftyTwoWeekHigh"),
            fiftyTwoWeekLow: extractRawDouble(summaryModule, "fiftyTwoWeekLow"),
            fiftyDayMovingAverage: extractRawDouble(summaryModule, "fiftyDayAverage"),
            twoHundredDayMovingAverage: extractRawDouble(summaryModule, "twoHundredDayAverage")
        )
    }

    // MARK: - Chart Data

    static func parseChartData(data: Data) throws -> ChartData {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let chart = root["chart"] as? [String: Any],
              let results = chart["result"] as? [[String: Any]],
              let result = results.first,
              let rawTimestamps = result["timestamp"] as? [Double],
              let indicators = result["indicators"] as? [String: Any],
              let quoteArr = indicators["quote"] as? [[String: Any]],
              let quote = quoteArr.first,
              let closePrices = quote["close"] as? [Any] else {
            throw YahooFinanceError.parseFailure("No chart data available.")
        }

        let tradingVolumes = quote["volume"] as? [Any]

        var timestamps: [TimeInterval] = []
        var prices: [Double] = []
        var volumes: [Int] = []

        let count = min(rawTimestamps.count, closePrices.count)
        for i in 0..<count {
            // Skip bars where close is null
            guard let price = closePrices[i] as? Double else { continue }

            timestamps.append(rawTimestamps[i])
            prices.append(price)

            var volume = 0
            if let vols = tradingVolumes, i < vols.count, let v = vols[i] as? Int {
                volume = v
            } else if let vols = tradingVolumes, i < vols.count, let v = vols[i] as? Double {
                volume = Int(v)
            }
            volumes.append(volume)
        }

        return ChartData(timestamps: timestamps, prices: prices, volumes: volumes)
    }

    // MARK: - News

    static func parseNewsItems(data: Data) -> [NewsItem] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let newsArray = root["news"] as? [[String: Any]] else {
            return []
        }

        return newsArray.compactMap { article in
            guard let title = article["title"] as? String,
                  let url = article["link"] as? String else { return nil }
            let publisher = article["publisher"] as? String ?? "Unknown"
            let published = article["providerPublishTime"] as? Double ?? 0
            return NewsItem(title: title, publisher: publisher, url: url, publishedAt: published)
        }
    }

    // MARK: - Screener

    static func parseScreenerStocks(data: Data) -> [ScreenerStock] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let finance = root["finance"] as? [String: Any],
              let results = finance["result"] as? [[String: Any]],
              let result = results.first,
              let quotes = result["quotes"] as? [[String: Any]] else {
            return []
        }

        return quotes.compactMap { quote in
            guard let symbol = quote["symbol"] as? String, !symbol.isEmpty else { return nil }

            let exchange = (quote["fullExchangeName"] as? String) ?? (quote["exchange"] as? String)
            if let exch = exchange, !isAcceptedUSExchange(exch) { return nil }

            let name = (quote["shortName"] as? String) ?? (quote["longName"] as? String) ?? symbol

            return ScreenerStock(
                symbol: symbol,
                name: name,
                exchange: exchange ?? "",
                currency: (quote["currency"] as? String) ?? "USD",
                price: extractRawDouble(quote, "regularMarketPrice") ?? 0,
                changePercent: extractRawDouble(quote, "regularMarketChangePercent") ?? 0,
                marketCap: extractRawDouble(quote, "marketCap") ?? 0,
                volume: Int(extractRawDouble(quote, "regularMarketVolume") ?? 0),
                peRatio: extractRawDouble(quote, "trailingPE"),
                beta: extractRawDouble(quote, "beta"),
                dividendYield: extractRawDouble(quote, "dividendYield")
            )
        }
    }

    // MARK: - Options Chain

    static func parseOptionsChain(ticker: String, data: Data) throws -> OptionsChain {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let optionChain = root["optionChain"] as? [String: Any],
              let results = optionChain["result"] as? [[String: Any]],
              let result = results.first else {
            throw YahooFinanceError.parseFailure("No options data available for \(ticker)")
        }

        let expirationDates = (result["expirationDates"] as? [Double]) ?? []
        let quoteDict = result["quote"] as? [String: Any] ?? [:]
        let underlyingPrice = extractRawDouble(quoteDict, "regularMarketPrice") ?? 0

        var calls: [OptionsContract] = []
        var puts: [OptionsContract] = []

        if let optionsArr = result["options"] as? [[String: Any]], let options = optionsArr.first {
            if let callsArr = options["calls"] as? [[String: Any]] {
                calls = callsArr.compactMap { parseContract($0) }
            }
            if let putsArr = options["puts"] as? [[String: Any]] {
                puts = putsArr.compactMap { parseContract($0) }
            }
        }

        return OptionsChain(
            ticker: ticker,
            underlyingPrice: underlyingPrice,
            expirationDates: expirationDates,
            calls: calls,
            puts: puts
        )
    }

    private static func parseContract(_ obj: [String: Any]) -> OptionsContract? {
        guard let strike = extractRawDouble(obj, "strike") else { return nil }

        return OptionsContract(
            contractSymbol: (obj["contractSymbol"] as? String) ?? "",
            strike: strike,
            lastPrice: extractRawDouble(obj, "lastPrice") ?? 0,
            bid: extractRawDouble(obj, "bid") ?? 0,
            ask: extractRawDouble(obj, "ask") ?? 0,
            change: extractRawDouble(obj, "change") ?? 0,
            changePercent: extractRawDouble(obj, "percentChange") ?? 0,
            volume: Int(extractRawDouble(obj, "volume") ?? 0),
            openInterest: Int(extractRawDouble(obj, "openInterest") ?? 0),
            impliedVolatility: extractRawDouble(obj, "impliedVolatility") ?? 0,
            inTheMoney: (obj["inTheMoney"] as? Bool) ?? false,
            expiration: extractRawDouble(obj, "expiration") ?? 0
        )
    }

    // MARK: - Earnings Calendar

    static func parseEarningsEntry(ticker: String, data: Data) -> EarningsEntry? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let quoteSummary = root["quoteSummary"] as? [String: Any],
              let results = quoteSummary["result"] as? [[String: Any]],
              let result = results.first else { return nil }

        let priceModule = result["price"] as? [String: Any] ?? [:]
        let companyName = extractString(priceModule, "longName")
            ?? extractString(priceModule, "shortName")
            ?? ticker

        guard let calEvents = result["calendarEvents"] as? [String: Any],
              let earnings = calEvents["earnings"] as? [String: Any] else { return nil }

        // Earnings date — array because Yahoo sometimes returns a range
        var earningsDate: TimeInterval?
        if let datesArr = earnings["earningsDate"] as? [Any], let first = datesArr.first {
            if let dict = first as? [String: Any], let raw = dict["raw"] as? Double {
                earningsDate = raw
            } else if let raw = first as? Double {
                earningsDate = raw
            }
        }
        guard let earningsDate else { return nil }

        let earningsTime = (earnings["earningsCallTime"] as? String) ?? "—"

        return EarningsEntry(
            ticker: ticker,
            companyName: companyName,
            earningsDate: earningsDate,
            earningsTime: earningsTime.isEmpty ? "—" : earningsTime,
            epsEstimate: extractRawDouble(earnings, "epsEstimate"),
            epsActual: extractRawDouble(earnings, "epsActual")
        )
    }

    // MARK: - Dividend Calendar

    static func parseDividendEntry(ticker: String, data: Data) -> DividendEntry? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let quoteSummary = root["quoteSummary"] as? [String: Any],
              let results = quoteSummary["result"] as? [[String: Any]],
              let result = results.first else { return nil }

        let priceModule = result["price"] as? [String: Any] ?? [:]
        let companyName = extractString(priceModule, "longName")
            ?? extractString(priceModule, "shortName")
            ?? ticker

        // Ex-dividend date
        guard let calEvents = result["calendarEvents"] as? [String: Any] else { return nil }
        var exDivDate: TimeInterval = 0
        if let exDivElement = calEvents["exDividendDate"] {
            if let dict = exDivElement as? [String: Any], let raw = dict["raw"] as? Double {
                exDivDate = raw
            } else if let raw = exDivElement as? Double {
                exDivDate = raw
            }
        }
        guard exDivDate > 0 else { return nil }

        let summaryDetail = result["summaryDetail"] as? [String: Any] ?? [:]
        let dividendAmount = extractRawDouble(summaryDetail, "dividendRate") ?? 0
        let dividendYield = (extractRawDouble(summaryDetail, "dividendYield") ?? 0) * 100.0
        let freqVal = extractRawDouble(summaryDetail, "dividendFrequency") ?? 0
        let frequency: String
        switch Int(freqVal) {
        case 4:  frequency = "Quarterly"
        case 12: frequency = "Monthly"
        case 2:  frequency = "Semi-Annual"
        case 1:  frequency = "Annual"
        default: frequency = "—"
        }

        return DividendEntry(
            ticker: ticker,
            companyName: companyName,
            exDividendDate: exDivDate,
            dividendAmount: dividendAmount,
            dividendYield: dividendYield,
            frequency: frequency
        )
    }

    // MARK: - Sector Info

    static func parseSectorInfo(data: Data) -> YahooFinanceService.SectorInfo {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let quoteSummary = root["quoteSummary"] as? [String: Any],
              let results = quoteSummary["result"] as? [[String: Any]],
              let result = results.first,
              let profile = result["assetProfile"] as? [String: Any] else {
            return YahooFinanceService.SectorInfo(sector: "Unknown", industry: "Unknown")
        }

        return YahooFinanceService.SectorInfo(
            sector: (profile["sector"] as? String) ?? "Unknown",
            industry: (profile["industry"] as? String) ?? "Unknown"
        )
    }

    // MARK: - JSON Helpers

    /// Extracts a string value from a dictionary.
    private static func extractString(_ dict: [String: Any], _ key: String) -> String? {
        dict[key] as? String
    }

    /// Extracts a numeric value, handling both plain numbers and Yahoo's
    /// `{"raw": 173.5, "fmt": "173.50"}` wrapper format.
    static func extractRawDouble(_ dict: [String: Any], _ key: String) -> Double? {
        guard let value = dict[key] else { return nil }

        // Unwrap {"raw": ..., "fmt": ...} objects
        if let wrapper = value as? [String: Any], let raw = wrapper["raw"] {
            if let d = raw as? Double { return d }
            if let i = raw as? Int { return Double(i) }
            return nil
        }

        // Plain number
        if let d = value as? Double { return d }
        if let i = value as? Int { return Double(i) }
        return nil
    }

    /// Returns whether the given Yahoo exchange label represents NASDAQ or NYSE.
    private static func isAcceptedUSExchange(_ exchange: String) -> Bool {
        let upper = exchange.uppercased()
        return upper.contains("NASDAQ") || upper.contains("NMS")
            || upper.contains("NYQ") || upper.contains("NYSE")
            || upper.contains("NGM") || upper.contains("NCM")
    }
}
