import SwiftUI
import AVFoundation

@main
struct HighSpeedRecorderApp: App {
    var body: some Scene { WindowGroup { RecorderScreen() } }
}
struct RecorderScreen: View {
    @StateObject private var recorder = Recorder()
    @State private var mode: CaptureMode = .fullHD240
    @State private var lockCamera = true
    @State private var imuHz = 1000.0
    @State private var timed = false
    @State private var seconds = "60"
    @Environment(\.scenePhase) private var phase
    private var validDuration: Bool {
        guard timed else { return true }
        guard let value = Double(seconds) else { return false }
        return value.isFinite && value > 0
    }
    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("High-Speed Recorder").font(.headline)
                Preview(session: recorder.session).aspectRatio(16.0 / 9.0, contentMode: .fit).clipped()
                Text(recorder.rateSummary).font(.caption.monospacedDigit())
                Text(recorder.status).font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Picker("Video", selection: $mode) {
                        ForEach(recorder.supportedModes) { Text($0.label).tag($0) }
                    }.disabled(recorder.busy)
                    Picker("IMU request", selection: $imuHz) {
                        Text("Maximum-rate probe (1000 Hz request)").tag(1000.0)
                        Text("500 Hz request").tag(500.0)
                        Text("200 Hz request").tag(200.0)
                        Text("100 Hz request").tag(100.0)
                    }.disabled(recorder.busy)
                    Text("iOS may cap sensor rates. Read measured Hz, not the requested value.")
                        .font(.caption2).foregroundStyle(.secondary)
                    Toggle("Lock current focus / exposure / WB", isOn: $lockCamera).disabled(recorder.busy)
                    Toggle("Optional time limit", isOn: $timed).disabled(recorder.busy)
                    if timed {
                        TextField("Seconds", text: $seconds).keyboardType(.decimalPad)
                            .textFieldStyle(.roundedBorder).disabled(recorder.busy)
                    }
                    HStack {
                        Button("Start") {
                            recorder.start(lockCamera: lockCamera, imuHz: imuHz,
                                           durationSeconds: timed ? Double(seconds) : nil)
                        }.disabled(recorder.busy || !recorder.ready || !validDuration)
                        Button("Stop & Save") { recorder.stop() }.disabled(!recorder.busy)
                    }.buttonStyle(.borderedProminent)
                    Button("10-second recording check") {
                        recorder.start(lockCamera: lockCamera, imuHz: imuHz, durationSeconds: 10)
                    }.disabled(recorder.busy || !recorder.ready).buttonStyle(.bordered)
                    Text("Let preview focus and exposure settle before Start. For a fresh focus adjustment, reselect the video mode. Keep the app open and the phone in the same landscape orientation.")
                        .font(.caption2).foregroundStyle(.secondary)
                    if !recorder.lastFolderName.isEmpty {
                        Text("Saved folder: \(recorder.lastFolderName)").font(.caption2).textSelection(.enabled)
                    }
                    Text("Export: Files → On My iPhone → HighSpeedRecorder. Copy the whole Recording folder.")
                        .font(.caption2)
                    if !recorder.ready && !recorder.busy {
                        Button("Retry camera") { recorder.prepare(mode: mode) }
                    }
                }
            }.frame(maxWidth: .infinity)
        }.padding()
        .onAppear { recorder.prepare(mode: mode) }
        .onChange(of: mode) { value in recorder.prepare(mode: value) }
        .onChange(of: phase) { value in
            if value == .background { recorder.backgrounded() }
        }
    }
}
private final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    override func layoutSubviews() {
        super.layoutSubviews()
        if let c = preview.connection, c.isVideoOrientationSupported { c.videoOrientation = .landscapeRight }
    }
}
private struct Preview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.preview.session = session
        view.preview.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
