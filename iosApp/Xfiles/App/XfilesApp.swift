import SwiftUI

@main
@MainActor
struct XfilesApp: App {
    @State private var library = LibraryModel()
    @AppStorage("xfiles.appearance") private var appearance = Appearance.system

    var body: some Scene {
        WindowGroup {
            LibraryScreen(model: library, appearance: $appearance)
                .preferredColorScheme(appearance.scheme)
        }
    }
}

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: Self { self }
    var scheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct SettingsScreen: View {
    @Binding var appearance: Appearance

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Picker("Theme", selection: $appearance) {
                        ForEach(Appearance.allCases) { theme in
                            Text(theme.rawValue.capitalized).tag(theme)
                        }
                    }
                }
                Section("About") {
                    LabeledContent("App", value: "Xfiles")
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                    Text("Imported files are stored in Xfiles for offline access.")
                        .foregroundStyle(.secondary)
                    if let source = URL(string: "https://github.com/kishore-bot/X-Files") {
                        Link("Source code", destination: source)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
