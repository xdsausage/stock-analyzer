import XCTest
@testable import MobilePortStock

final class OptionsAnalyticsTests: XCTestCase {

    // ATM call: spot=100, strike=100, IV=0.3, 30 DTE (≈0.0822 years)
    private func makeATMCall() -> OptionsContract {
        let expiration = Date().timeIntervalSince1970 + 30 * 24 * 3600
        return OptionsContract(
            contractSymbol: "TEST230101C100",
            strike: 100, lastPrice: 3.5,
            bid: 3.4, ask: 3.6,
            change: 0, changePercent: 0,
            volume: 100, openInterest: 500,
            impliedVolatility: 0.30,
            inTheMoney: false,
            expiration: expiration
        )
    }

    func testDeltaATMCallNearHalf() {
        let contract = makeATMCall()
        let now = Date().timeIntervalSince1970
        let analysis = OptionsAnalytics.analyze(contract, spotPrice: 100, valuationEpoch: now, isCall: true)
        // ATM call delta should be near 0.5 (range 0.45–0.55 is acceptable)
        XCTAssertTrue(analysis.delta > 0.45 && analysis.delta < 0.55,
                      "ATM call delta expected ~0.5, got \(analysis.delta)")
    }

    func testPutDeltaNegative() {
        let contract = makeATMCall()
        let now = Date().timeIntervalSince1970
        let analysis = OptionsAnalytics.analyze(contract, spotPrice: 100, valuationEpoch: now, isCall: false)
        XCTAssertTrue(analysis.delta < 0, "Put delta should be negative, got \(analysis.delta)")
    }

    func testGammaPositive() {
        let contract = makeATMCall()
        let now = Date().timeIntervalSince1970
        let analysis = OptionsAnalytics.analyze(contract, spotPrice: 100, valuationEpoch: now, isCall: true)
        XCTAssertTrue(analysis.gamma > 0, "Gamma should be positive")
    }

    func testThetaNegative() {
        let contract = makeATMCall()
        let now = Date().timeIntervalSince1970
        let analysis = OptionsAnalytics.analyze(contract, spotPrice: 100, valuationEpoch: now, isCall: true)
        XCTAssertTrue(analysis.thetaPerDay < 0, "Theta should be negative (time decay)")
    }

    func testVegaPositive() {
        let contract = makeATMCall()
        let now = Date().timeIntervalSince1970
        let analysis = OptionsAnalytics.analyze(contract, spotPrice: 100, valuationEpoch: now, isCall: true)
        XCTAssertTrue(analysis.vegaPerVolPoint > 0, "Vega should be positive")
    }

    func testBreakEvenCall() {
        let contract = makeATMCall()
        let now = Date().timeIntervalSince1970
        let analysis = OptionsAnalytics.analyze(contract, spotPrice: 100, valuationEpoch: now, isCall: true)
        // Break-even = strike + midpoint = 100 + 3.5 = 103.5
        XCTAssertEqual(analysis.breakEven, 103.5, accuracy: 0.01)
    }

    func testMaxPain_singleStrike() {
        let strike = 100.0
        let calls = [OptionsContract(contractSymbol: "C", strike: strike,
                                     lastPrice: 1, bid: 0.9, ask: 1.1,
                                     change: 0, changePercent: 0,
                                     volume: 10, openInterest: 100,
                                     impliedVolatility: 0.3, inTheMoney: false,
                                     expiration: Date().timeIntervalSince1970)]
        let puts = [OptionsContract(contractSymbol: "P", strike: strike,
                                    lastPrice: 1, bid: 0.9, ask: 1.1,
                                    change: 0, changePercent: 0,
                                    volume: 10, openInterest: 100,
                                    impliedVolatility: 0.3, inTheMoney: false,
                                    expiration: Date().timeIntervalSince1970)]
        let maxPain = OptionsAnalytics.computeMaxPain(calls: calls, puts: puts)
        XCTAssertEqual(maxPain, strike, accuracy: 0.01)
    }
}
