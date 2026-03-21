import XCTest

final class MobilePortStockUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    func testAppLaunchesSuccessfully() {
        XCTAssertTrue(app.state == .runningForeground)
    }

    func testQuoteTabExists() {
        let quoteTab = app.tabBars.buttons["Quote"]
        XCTAssertTrue(quoteTab.exists)
    }

    func testAllMainTabsExist() {
        XCTAssertTrue(app.tabBars.buttons["Quote"].exists)
        XCTAssertTrue(app.tabBars.buttons["Screener"].exists)
        XCTAssertTrue(app.tabBars.buttons["Portfolio"].exists)
        XCTAssertTrue(app.tabBars.buttons["News"].exists)
        XCTAssertTrue(app.tabBars.buttons["More"].exists)
    }
}
