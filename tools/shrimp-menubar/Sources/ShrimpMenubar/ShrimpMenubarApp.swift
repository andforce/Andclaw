import SwiftUI
import AppKit

@main
struct ShrimpMenubarApp: App {
    @StateObject private var model = AppModel()

    init() {
        NSApplication.shared.setActivationPolicy(.accessory)
    }

    var body: some Scene {
        MenuBarExtra {
            ContentView()
                .environmentObject(model)
        } label: {
            Label(model.menuTitle, systemImage: model.statusIconName)
        }
        .menuBarExtraStyle(.menu)
    }
}

private struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ShrimpMenubar")
                .font(.headline)

            Text("Multi-relay Feishu login + local ws bridge")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)

            if let warning = model.globalWarning, !warning.isEmpty {
                Divider()
                Text(warning)
                    .font(.system(size: 11))
                    .foregroundStyle(.orange)
                    .frame(maxWidth: 360, alignment: .leading)
            }

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(model.relays, id: \.id) { relay in
                        RelaySectionView(relay: relay)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 420)

            Divider()

            HStack {
                Button("Settings") { SettingsWindowController.shared.show(model: model) }
                Button("Open raw config") { model.openConfig() }
                Spacer()
                Button("Stop All") { model.stopAll() }
                Button("Quit") {
                    model.stopAll()
                    NSApplication.shared.terminate(nil)
                }
            }
        }
        .padding(12)
        .frame(width: 420)
    }
}

private struct FlowButtonRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack {
                content
            }
            VStack(alignment: .leading, spacing: 6) {
                content
            }
        }
    }
}

private struct RelaySectionView: View {
    @ObservedObject var relay: RelayRuntime

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label(relay.name, systemImage: relay.statusIconName)
                        .font(.headline)
                    Spacer()
                    Text(relay.localRelayURL)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                }

                statusRow("Listen mode", relay.listenModeText)
                statusRow("Feishu login", relay.loginStateText)
                statusRow("Login helper", relay.loginHelperStateText)
                statusRow("Relay", relay.bridgeStateText)
                if let team = relay.teamFeishuURL {
                    statusRow("Team URL", team)
                }

                Text(relay.summaryLine)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let detail = relay.lastError, !detail.isEmpty {
                    Text(detail)
                        .font(.system(size: 11))
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                FlowButtonRow {
                    Button("Start Relay") { relay.startRelay() }
                    Button("Stop Relay") { relay.stopRelay() }
                    Button("Check Login") {
                        Task { await relay.checkLoginState() }
                    }
                    Button("One-click Start") { relay.startAll() }
                }

                FlowButtonRow {
                    Button("Open Feishu Login") { relay.openFeishuLogin() }
                    Button("Stop Login") { relay.stopLoginHelper() }
                }

                FlowButtonRow {
                    Button("Open Team Feishu") { relay.openTeamFeishu() }
                    Button("Open Overview") { relay.openOverview() }
                    Button("Login Log") { relay.openLoginLog() }
                    Button("Bridge Log") { relay.openBridgeLog() }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } label: {
            EmptyView()
        }
    }

    @ViewBuilder
    private func statusRow(_ title: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(title)
                .frame(width: 88, alignment: .leading)
            Spacer()
            Text(value)
                .multilineTextAlignment(.trailing)
                .foregroundStyle(.secondary)
        }
        .font(.system(size: 12))
    }
}

@MainActor
private final class SettingsWindowController: NSObject, NSWindowDelegate {
    static let shared = SettingsWindowController()

    private var window: NSWindow?

    func show(model: AppModel) {
        let hosting = NSHostingController(rootView: SettingsView(model: model, onClose: { [weak self] in
            self?.window?.close()
        }))

        if let window {
            window.contentViewController = hosting
            window.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 760, height: 720),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "ShrimpMenubar Settings"
        window.isReleasedWhenClosed = false
        window.center()
        window.contentViewController = hosting
        window.delegate = self
        self.window = window
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func windowWillClose(_ notification: Notification) {
        window?.contentViewController = nil
    }
}

private struct SettingsView: View {
    @ObservedObject var model: AppModel
    let onClose: () -> Void
    @State private var draft: [RelayConfig]

    init(model: AppModel, onClose: @escaping () -> Void) {
        self.model = model
        self.onClose = onClose
        _draft = State(initialValue: model.relays.map(\.config))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Settings")
                    .font(.title3)
                    .bold()
                Spacer()
                Button("Add relay") {
                    draft.append(model.makeNewRelayTemplate(index: draft.count + 1))
                }
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach($draft) { $relay in
                        RelayEditorView(relay: $relay, canDelete: draft.count > 1) {
                            draft.removeAll { $0.id == relay.id }
                        }
                    }
                }
                .padding(.vertical, 4)
            }

            HStack {
                Button("Open raw config") { model.openConfig() }
                Spacer()
                Button("Cancel") { onClose() }
                Button("Save") {
                    model.saveConfigs(draft)
                    onClose()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(16)
        .frame(width: 720, height: 660)
    }
}

private struct RelayEditorView: View {
    @Binding var relay: RelayConfig
    let canDelete: Bool
    let onDelete: () -> Void

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    TextField("Relay name", text: $relay.name)
                    Spacer()
                    if canDelete {
                        Button("Delete", role: .destructive, action: onDelete)
                    }
                }

                TextField("Stable id", text: $relay.id)
                Picker("Local listen mode", selection: $relay.listenMode) {
                    ForEach(ListenMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                TextField("Local relay port", value: $relay.bridgePort, format: .number)
                TextField("Remote WSS URL", text: $relay.remoteGatewayWSS)
                TextField("Gateway token", text: $relay.gatewayToken)

                DisclosureGroup("Advanced") {
                    TextField("Browser profile dir", text: $relay.browserProfileDir)
                    TextField("Browser channel (chromium/chrome)", text: $relay.browserChannel)
                    TextField("Relay script path", text: $relay.relayScriptPath)
                    TextField("Bridge script path", text: $relay.bridgeScriptPath)
                    TextField("node binary", text: $relay.nodeBinary)
                    TextField("Login log path", text: $relay.loginLogPath)
                    TextField("Bridge log path", text: $relay.bridgeLogPath)
                }
            }
        } label: {
            Text(relay.name.isEmpty ? relay.id : relay.name)
                .font(.headline)
        }
    }
}
