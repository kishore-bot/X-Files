import AVFoundation
import AudioToolbox
import SwiftUI

struct MediaSourceDiagnostics {
    struct Track: Identifiable {
        let id: CMPersistentTrackID
        let language: String?
        let format: String
        let playable: Bool
        let channels: UInt32
        let losslessRate: Double?
    }

    var audio: [Track] = []
    var hasVideo = false
    var sourceColor: String?

    @MainActor
    static func load(from asset: AVAsset) async -> Self {
        var result = Self()
        let audioTracks = (try? await asset.loadTracks(withMediaType: .audio)) ?? []
        for track in audioTracks {
            if Task.isCancelled { break }
            let playable = (try? await track.load(.isPlayable)) ?? true
            let language = try? await track.load(.extendedLanguageTag)
            let descriptions = (try? await track.load(.formatDescriptions)) ?? []
            let channels = descriptions.compactMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee.mChannelsPerFrame }.max() ?? 0
            let formats = descriptions.map { description in
                let code = CMFormatDescriptionGetMediaSubType(description)
                let tags = switch code {
                case kAudioFormatAppleLossless: "ALAC · lossless source"
                case kAudioFormatFLAC: "FLAC · lossless source"
                case kAudioFormatLinearPCM: "PCM · lossless source"
                case kAudioFormatAC3: "Dolby Digital"
                case kAudioFormatEnhancedAC3: "Dolby Digital Plus"
                default: fourCC(code)
                }
                guard let stream = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee else { return tags }
                var fields = [tags]
                if stream.mChannelsPerFrame > 0 { fields.append("\(stream.mChannelsPerFrame) ch") }
                if stream.mSampleRate.isFinite, stream.mSampleRate > 0, stream.mSampleRate <= 768_000 {
                    fields.append("\(Int(stream.mSampleRate)) Hz source")
                }
                if stream.mBitsPerChannel > 0 { fields.append("\(stream.mBitsPerChannel)-bit source") }
                return fields.joined(separator: " · ")
            }
            let losslessFormats: Set<AudioFormatID> = [kAudioFormatAppleLossless, kAudioFormatFLAC, kAudioFormatLinearPCM]
            let rates = descriptions.compactMap { description -> Double? in
                guard losslessFormats.contains(CMFormatDescriptionGetMediaSubType(description)),
                      let rate = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee.mSampleRate,
                      rate.isFinite, rate > 0, rate <= 768_000 else { return nil }
                return rate
            }
            let losslessRate = rates.count == descriptions.count && Set(rates).count == 1 ? rates.first : nil
            result.audio.append(Track(
                id: track.trackID, language: language,
                format: formats.isEmpty ? "Format unreported" : formats.joined(separator: ", "),
                playable: playable, channels: channels, losslessRate: losslessRate
            ))
        }
        let video = (try? await asset.loadTracks(withMediaType: .video)) ?? []
        result.hasVideo = !video.isEmpty
        if let track = video.first, let descriptions = try? await track.load(.formatDescriptions) {
            result.sourceColor = descriptions.compactMap { description -> String? in
                let codec = fourCC(CMFormatDescriptionGetMediaSubType(description))
                if ["dvh1", "dvhe", "dva1", "dvav"].contains(codec) { return "Dolby Vision" }
                let transfer = CMFormatDescriptionGetExtension(
                    description, extensionKey: kCMFormatDescriptionExtension_TransferFunction
                ) as? String
                if transfer == kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String { return "PQ HDR" }
                if transfer == kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String { return "HLG" }
                if transfer == kCMFormatDescriptionTransferFunction_ITU_R_709_2 as String { return "SDR" }
                return nil
            }.first
        }
        return result
    }

    private static func fourCC(_ code: UInt32) -> String {
        let bytes = [24, 16, 8, 0].map { UInt8(truncatingIfNeeded: code >> $0) }
        return bytes.allSatisfy { (32...126).contains($0) }
            ? String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)
            : "Format unreported"
    }
}

struct MediaRouteDetails: View {
    let engine: String
    var reportsSystemSpatialSupport = true
    var showsHDR = false
    var usbStatus: String? = nil
    @State private var usbConnected = false
    @State private var outputs = ""
    @State private var rate: Double = 0
    @State private var spatial = false
    @State private var hdr = ""

    var body: some View {
        Section("Playback diagnostics") {
            LabeledContent("Engine", value: engine)
            LabeledContent("Audio route", value: outputs.isEmpty ? "Unreported" : outputs)
            if rate.isFinite && rate > 0 && rate <= 768_000 {
                LabeledContent("Session sample rate", value: "\(Int(rate)) Hz")
            }
            if usbConnected {
                LabeledContent("USB audio", value: usbStatus ?? "System-managed")
            }
            LabeledContent("Output quality", value: "Unverified")
            if reportsSystemSpatialSupport {
                LabeledContent("System spatial audio", value: spatial ? "Available on this route" : "Unavailable on this route")
            }
            if showsHDR {
                if !hdr.isEmpty { LabeledContent("System HDR modes", value: hdr) }
                LabeledContent("HDR output", value: "Unverified")
            }
        }
        .onAppear(perform: refresh)
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { _ in refresh() }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.spatialPlaybackCapabilitiesChangedNotification)) { _ in refresh() }
        .task {
            while !Task.isCancelled {
                refresh()
                do { try await Task.sleep(for: .seconds(2)) } catch { break }
            }
        }
    }

    private func refresh() {
        let session = AVAudioSession.sharedInstance()
        let ports = session.currentRoute.outputs
        usbConnected = ports.count == 1 && ports.first?.portType == .usbAudio
        outputs = ports.map(\.portName).joined(separator: ", ")
        spatial = ports.contains(where: \.isSpatialAudioEnabled)
        rate = session.sampleRate
        if showsHDR {
            let modes = AVPlayer.availableHDRModes
            hdr = [
                modes.contains(.hdr10) ? "HDR10" : nil,
                modes.contains(.hlg) ? "HLG" : nil,
                modes.contains(.dolbyVision) ? "Dolby Vision" : nil,
            ].compactMap { $0 }.joined(separator: ", ")
        }
    }
}


/// Requests the known source rate for USB audio. iOS owns the session mixer and device format.
@MainActor
final class MediaUSBRatePreference {
    private var routeID: String?
    private var attemptedRate: Double?
    private var restoreRate: Double?
    private var appliedRate: Double?

    func update(session: AVAudioSession, sourceRate: Double?) -> String? {
        let outputs = session.currentRoute.outputs
        guard outputs.count == 1, let port = outputs.first, port.portType == .usbAudio else {
            clear(session: session)
            return nil
        }
        guard let sourceRate else {
            clear(session: session)
            return "USB audio connected"
        }
        if routeID != port.uid || attemptedRate != sourceRate {
            clear(session: session)
            routeID = port.uid
            attemptedRate = sourceRate
            if abs(session.sampleRate - sourceRate) >= 0.5 {
                let previous = session.preferredSampleRate > 0 ? session.preferredSampleRate : session.sampleRate
                do {
                    try session.setPreferredSampleRate(sourceRate)
                    restoreRate = previous > 0 ? previous : nil
                    appliedRate = sourceRate
                } catch {
                    return "USB audio connected"
                }
            }
        }
        return abs(session.sampleRate - sourceRate) < 0.5
            ? "USB audio · source sample rate matched" : "USB audio connected"
    }

    func clear(session: AVAudioSession) {
        if let restoreRate, let appliedRate, session.preferredSampleRate == appliedRate {
            try? session.setPreferredSampleRate(restoreRate)
        }
        routeID = nil
        attemptedRate = nil
        restoreRate = nil
        appliedRate = nil
    }
}
