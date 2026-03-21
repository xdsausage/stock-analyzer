import XCTest
@testable import MobilePortStock

final class IndicatorCalculatorTests: XCTestCase {

    // MARK: - SMA

    func testSMA_basicCase() {
        let prices = [1.0, 2.0, 3.0, 4.0, 5.0]
        let result = IndicatorCalculator.computeMovingAverage(prices, period: 3)

        XCTAssertNil(result[0])
        XCTAssertNil(result[1])
        XCTAssertEqual(result[2], 2.0, accuracy: 0.001)
        XCTAssertEqual(result[3], 3.0, accuracy: 0.001)
        XCTAssertEqual(result[4], 4.0, accuracy: 0.001)
    }

    func testSMA_periodLargerThanInput() {
        let prices = [1.0, 2.0]
        let result = IndicatorCalculator.computeMovingAverage(prices, period: 5)
        XCTAssertTrue(result.allSatisfy { $0 == nil })
    }

    // MARK: - RSI

    func testRSI_outputLength() {
        let prices = (1...30).map { Double($0) }
        let result = IndicatorCalculator.computeRSI(prices, period: 14)
        XCTAssertEqual(result.count, 30)
    }

    func testRSI_warmupIsNil() {
        let prices = (1...30).map { Double($0) }
        let result = IndicatorCalculator.computeRSI(prices, period: 14)
        // First 14 values should be nil
        for i in 0..<14 {
            XCTAssertNil(result[i], "Expected nil at index \(i)")
        }
    }

    func testRSI_allGainsIs100() {
        // Monotonically increasing prices → RSI should approach 100
        let prices = (1...30).map { Double($0) }
        let result = IndicatorCalculator.computeRSI(prices, period: 14)
        let valid = result.compactMap { $0 }
        XCTAssertTrue(valid.allSatisfy { $0 > 90 })
    }

    func testRSI_allLossesIs0() {
        let prices = (1...30).map { Double(31 - $0) }
        let result = IndicatorCalculator.computeRSI(prices, period: 14)
        let valid = result.compactMap { $0 }
        XCTAssertTrue(valid.allSatisfy { $0 < 10 })
    }

    // MARK: - MACD

    func testMACD_outputLengths() {
        let prices = (1...60).map { Double($0) }
        let result = IndicatorCalculator.computeMACD(prices)
        XCTAssertEqual(result.macdLine.count, 60)
        XCTAssertEqual(result.signalLine.count, 60)
        XCTAssertEqual(result.histogram.count, 60)
    }

    func testMACD_warmupIsNil() {
        let prices = (1...60).map { Double($0) }
        let result = IndicatorCalculator.computeMACD(prices, fastPeriod: 12, slowPeriod: 26, signalPeriod: 9)
        // Slow EMA needs 26 bars to seed → MACD line starts at index 25
        XCTAssertNil(result.macdLine[24])
        XCTAssertNotNil(result.macdLine[25])
        // Signal needs 9 more → first valid at index 25+8=33
        XCTAssertNil(result.signalLine[32])
        XCTAssertNotNil(result.signalLine[33])
    }
}
