# Xfiles for iOS

The app targets **iOS and iPadOS 17 or later**. Use macOS, Xcode with Swift 6 and an iOS 26 or newer SDK for Foundation Models, and rustup with the repo's pinned Rust toolchain. Install targets for the destinations you use.

```sh
rustup target add --toolchain 1.100.0 aarch64-apple-ios aarch64-apple-ios-sim
# Also install x86_64-apple-ios when using an Intel Mac simulator.
```

Open `Xfiles.xcodeproj` in Xcode and select the shared **Xfiles** scheme. Its build phase builds and links the Rust document engine for the selected destination. Select an iPhone or iPad simulator running iOS 17 or later. To use a physical device, configure your development team and bundle identifier under **Signing & Capabilities**.

Use **Import files** for offline reading. The Library menu includes **Search contents**, **Document tools**, **Scan document**, **Find duplicates**, and **Ask Fily**. The reader menu exposes actions for the open file. Editing and extraction create separate imported copies.

On devices where the Apple on-device model is unavailable, Fily still returns matching excerpts and can open files or navigate PDF pages. Microphone transcription is available only through on-device speech recognition. The scanner requires a supported physical camera.

See the [architecture and delivery plan](../docs/IOS_ARCHITECTURE.md) for platform limits and validation status.
