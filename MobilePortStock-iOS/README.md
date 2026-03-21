# MobilePortStock iOS

iOS/iPadOS port of the MobilePortStock stock analysis app.

## Setup

### Option A — XcodeGen (recommended, works on any machine)

1. Install XcodeGen on a Mac: `brew install xcodegen`
2. `cd MobilePortStock-iOS`
3. `xcodegen generate --spec project.yml`
4. Open `MobilePortStock.xcodeproj` in Xcode
5. Set your Team in the app target's Signing & Capabilities
6. Run on a simulator or device

### Option B — GitHub Actions (no Mac required)
Push to `main` or open a PR — the workflow at `.github/workflows/ios-build.yml`
automatically builds and runs unit tests on Apple's macOS runners.
Check the **Actions** tab in your GitHub repo for results.

### Option C — Manual Xcode project (legacy)
1. File → New → Project → iOS → App
2. Name: **MobilePortStock**, SwiftUI, SwiftData, iOS 17+
3. Save inside this directory
4. Drag in the source folders (App, Models, Services, Analytics, Persistence, ViewModels, Views)

## Architecture

| Layer | Description |
|-------|-------------|
| **App** | Entry point + adaptive nav (NavigationSplitView iPad / TabView iPhone) |
| **Models** | Swift `Codable` structs — all Java NaN replaced with optionals |
| **Services** | `actor YahooFinanceService` — async/await + crumb auth |
| **Analytics** | `IndicatorCalculator` (SMA/RSI/MACD) + `OptionsAnalytics` (Black-Scholes) |
| **Persistence** | SwiftData `@Model` classes for portfolio, watchlist, notes |
| **ViewModels** | `@Observable` MVVM — one per feature |
| **Views** | Modular SwiftUI — Swift Charts for all charting |

## Requirements

- iOS 17.0+ (for SwiftData + Swift Charts)
- Xcode 15.0+ / Swift 5.9+
- No third-party dependencies
