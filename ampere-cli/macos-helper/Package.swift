// swift-tools-version:6.0
import PackageDescription

/// `ampere-helper`: the Swift process Ampere's JVM surfaces spawn to reach a framework a JVM
/// cannot (AMPR-375). Today that is FoundationModels, Apple's on-device model, which is
/// Swift-only: it has no C or Objective-C headers for JNA, FFM or cinterop to bind.
///
/// `.macOS(.v13)` on purpose. The model needs macOS 26, but a helper that cannot launch on an
/// older Mac would read as "could not start" instead of the precise answer it can give there,
/// `osTooOld`. `#if canImport(FoundationModels)` and `#available` do the gating.
///
/// No dependencies, so building this package stays a cheap, offline, one-time cost on a shared
/// development Mac. `:ampere-cli:buildMacHelper` builds it, up-to-date-checked against
/// `Sources/` and this file. The test target adds nothing to that build.
let package = Package(
    name: "ampere-helper",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "ampere-helper", targets: ["AmpereHelper"]),
    ],
    targets: [
        .executableTarget(
            name: "AmpereHelper",
            path: "Sources/AmpereHelper",
            // The Swift 5 language mode: Swift 6's strict concurrency checking has nothing to add
            // to a process that handles one request at a time.
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // `swift test --package-path ampere-cli/macos-helper` (AMPR-470). The wire cases need no
        // model. The live cases run only where the model generates once, and skip anywhere else.
        .testTarget(
            name: "AmpereHelperTests",
            dependencies: ["AmpereHelper"],
            path: "Tests/AmpereHelperTests",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
