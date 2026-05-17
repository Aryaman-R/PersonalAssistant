// swift-tools-version:5.7
//
// SentientHelper — small native CLI that runs on a user's Mac and lets the
// Sentient master drive OS-level actions (type text, click, launch apps,
// send keystrokes). Connects to the master over a separate `/helper`
// WebSocket so it isn't mixed into the browser broadcast set.
//
// Build:
//     swift build -c release
//     cp .build/release/SentientHelper /usr/local/bin/sentient-helper
//
// Run:
//     sentient-helper --host localhost:7070 --token <token-from-master> --name "Aryaman's Mac"

import PackageDescription

let package = Package(
    name: "SentientHelper",
    platforms: [
        .macOS(.v12)
    ],
    products: [
        .executable(name: "SentientHelper", targets: ["SentientHelper"])
    ],
    targets: [
        .executableTarget(
            name: "SentientHelper",
            path: "Sources/SentientHelper"
        )
    ]
)
