import AVFoundation
import Observation
import Speech

@MainActor
@Observable
final class OnDeviceSpeech {
    private(set) var listening = false
    private(set) var starting = false
    private(set) var message: String?
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognizer: SFSpeechRecognizer?
    private var recognition: SFSpeechRecognitionTask?
    private var tapped = false
    private var generation = UUID()
    private var audio: AudioConfiguration?

    private struct AudioConfiguration {
        let category: AVAudioSession.Category
        let mode: AVAudioSession.Mode
        let options: AVAudioSession.CategoryOptions
    }

    // The audio tap appends samples while Speech consumes them. All lifecycle calls stay on the main actor.
    private final class BufferAppender: @unchecked Sendable {
        private let request: SFSpeechAudioBufferRecognitionRequest
        init(_ request: SFSpeechAudioBufferRecognitionRequest) { self.request = request }
        func append(_ buffer: AVAudioPCMBuffer) { request.append(buffer) }
    }

    func start(onText: @escaping @MainActor @Sendable (String) -> Void) async {
        guard !listening, !starting else { return }
        message = nil
        generation = UUID()
        let session = generation
        starting = true
        defer { if generation == session { starting = false } }
        guard let recognizer = SFSpeechRecognizer(), recognizer.supportsOnDeviceRecognition else {
            message = "On-device dictation is unavailable for this language. You can type your question."
            return
        }
        let permitted = await withCheckedContinuation { (resume: CheckedContinuation<Bool, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in resume.resume(returning: status == .authorized) }
        }
        guard generation == session, !Task.isCancelled else { return }
        guard permitted, await AVAudioApplication.requestRecordPermission() else {
            if generation == session { message = "Allow microphone and speech recognition in Settings to dictate." }
            return
        }
        guard generation == session, !Task.isCancelled else { return }

        let shared = AVAudioSession.sharedInstance()
        audio = AudioConfiguration(category: shared.category, mode: shared.mode, options: shared.categoryOptions)
        do {
            try shared.setCategory(.playAndRecord, mode: .measurement, options: .defaultToSpeaker)
            try shared.setActive(true)
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                throw EngineError(message: "No microphone is available.")
            }
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.requiresOnDeviceRecognition = true
            request.shouldReportPartialResults = true
            self.request = request
            self.recognizer = recognizer
            let appender = BufferAppender(request)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in appender.append(buffer) }
            tapped = true
            engine.prepare()
            try engine.start()
            listening = true
            recognition = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let text = result?.bestTranscription.formattedString
                let final = result?.isFinal == true
                let issue = error?.localizedDescription
                Task { @MainActor in
                    guard let self, self.generation == session else { return }
                    if let text { onText(text) }
                    if let issue { self.message = issue }
                    if final || issue != nil { self.stop() }
                }
            }
        } catch {
            message = error.localizedDescription
            stop()
        }
    }

    func stop() {
        generation = UUID()
        starting = false
        if engine.isRunning { engine.stop() }
        if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
        request?.endAudio()
        recognition?.cancel()
        recognition = nil
        request = nil
        recognizer = nil
        listening = false
        if let audio {
            let session = AVAudioSession.sharedInstance()
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            try? session.setCategory(audio.category, mode: audio.mode, options: audio.options)
            self.audio = nil
        }
    }
}
