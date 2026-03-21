import XCTest
@testable import MobilePortStock

final class ModelCodableTests: XCTestCase {

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    func testStockDataRoundTrip() throws {
        let original = StockData(
            symbol: "AAPL", companyName: "Apple Inc.",
            exchange: "NASDAQ", currency: "USD",
            currentPrice: 175.50, priceChange: 2.30,
            priceChangePercent: 1.33, marketCap: 2_700_000_000_000,
            tradingVolume: 55_000_000, averageDailyVolume: 60_000_000,
            peRatio: 28.5, forwardPE: 26.0, earningsPerShare: 6.16,
            priceToBook: 45.0, beta: 1.28, dividendYield: 0.0055,
            fiftyTwoWeekHigh: 198.23, fiftyTwoWeekLow: 124.17,
            fiftyDayMovingAverage: 180.0, twoHundredDayMovingAverage: 168.5
        )

        let data = try encoder.encode(original)
        let decoded = try decoder.decode(StockData.self, from: data)

        XCTAssertEqual(decoded.symbol, original.symbol)
        XCTAssertEqual(decoded.currentPrice, original.currentPrice, accuracy: 0.001)
        XCTAssertEqual(decoded.peRatio, original.peRatio)
        XCTAssertEqual(decoded.dividendYield, original.dividendYield)
        XCTAssertEqual(decoded.beta, original.beta)
    }

    func testChartDataRoundTrip() throws {
        let original = ChartData(
            timestamps: [1_700_000_000, 1_700_003_600],
            prices: [150.0, 151.5],
            volumes: [1_000_000, 1_200_000]
        )

        let data = try encoder.encode(original)
        let decoded = try decoder.decode(ChartData.self, from: data)

        XCTAssertEqual(decoded.timestamps, original.timestamps)
        XCTAssertEqual(decoded.prices, original.prices)
        XCTAssertEqual(decoded.volumes, original.volumes)
    }

    func testNewsItemRoundTrip() throws {
        let original = NewsItem(
            title: "Apple Reports Record Revenue",
            publisher: "Reuters",
            url: "https://example.com/news/1",
            publishedAt: 1_700_000_000
        )

        let data = try encoder.encode(original)
        let decoded = try decoder.decode(NewsItem.self, from: data)

        XCTAssertEqual(decoded.title, original.title)
        XCTAssertEqual(decoded.publisher, original.publisher)
        XCTAssertEqual(decoded.url, original.url)
        XCTAssertEqual(decoded.publishedAt, original.publishedAt)
    }

    func testChartIntervalAllCases() {
        XCTAssertEqual(ChartInterval.allCases.count, 10)
        XCTAssertEqual(ChartInterval.defaultInterval, .oneMonth)
        XCTAssertEqual(ChartInterval.commodityDefault, .oneDay)
    }

    func testOptionsContractMidpoint() {
        let contract = OptionsContract(
            contractSymbol: "AAPL230101C150",
            strike: 150, lastPrice: 3.0,
            bid: 2.9, ask: 3.1,
            change: 0, changePercent: 0,
            volume: 100, openInterest: 500,
            impliedVolatility: 0.25, inTheMoney: false,
            expiration: 1_700_000_000
        )
        XCTAssertEqual(contract.midpoint, 3.0, accuracy: 0.001)
        XCTAssertEqual(contract.spread, 0.2, accuracy: 0.001)
    }

    func testStockDataOptionalNil() throws {
        // Fields that can be nil (no dividend, no P/E data)
        let stock = StockData(
            symbol: "XYZ", companyName: "XYZ Corp",
            exchange: "NYSE", currency: "USD",
            currentPrice: 10.0, priceChange: 0.0,
            priceChangePercent: 0.0, marketCap: 500_000_000,
            tradingVolume: 1_000_000, averageDailyVolume: 800_000,
            peRatio: nil, forwardPE: nil, earningsPerShare: nil,
            priceToBook: nil, beta: nil, dividendYield: nil,
            fiftyTwoWeekHigh: nil, fiftyTwoWeekLow: nil,
            fiftyDayMovingAverage: nil, twoHundredDayMovingAverage: nil
        )

        let data = try encoder.encode(stock)
        let decoded = try decoder.decode(StockData.self, from: data)
        XCTAssertNil(decoded.peRatio)
        XCTAssertNil(decoded.dividendYield)
        XCTAssertNil(decoded.beta)
    }
}
