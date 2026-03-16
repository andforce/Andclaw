// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ShrimpMenubar",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "ShrimpMenubar", targets: ["ShrimpMenubar"])
    ],
    targets: [
        .executableTarget(
            name: "ShrimpMenubar",
            path: "Sources/ShrimpMenubar"
        )
    ]
)
