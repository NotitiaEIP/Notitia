// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "NotitiaKit",
    defaultLocalization: "fr",
    platforms: [
        .macOS(.v26),
        .iOS(.v26),
    ],
    products: [
        .library(name: "NotitiaKit", targets: ["NotitiaKit"]),
    ],
    targets: [
        .target(
            name: "NotitiaKit",
            swiftSettings: [
                .enableUpcomingFeature("ExistentialAny"),
            ]
        ),
        .testTarget(
            name: "NotitiaKitTests",
            dependencies: ["NotitiaKit"]
        ),
    ]
)
