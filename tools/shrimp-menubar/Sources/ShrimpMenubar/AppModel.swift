import Foundation
import SwiftUI
import AppKit
import Darwin

enum ListenMode: String, CaseIterable, Codable, Identifiable {
    case localhost
    case lan

    var id: String { rawValue }

    var bindHost: String {
        switch self {
        case .localhost: return "127.0.0.1"
        case .lan: return "0.0.0.0"
        }
    }

    var title: String {
        switch self {
        case .localhost: return "localhost"
        case .lan: return "lan"
        }
    }
}

struct RelayConfig: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var listenMode: ListenMode
    var bridgePort: Int
    var remoteGatewayWSS: String
    var gatewayToken: String
    var browserProfileDir: String
    var browserChannel: String
    var relayScriptPath: String
    var bridgeScriptPath: String
    var nodeBinary: String
    var loginLogPath: String
    var bridgeLogPath: String

    static func template(index: Int) -> RelayConfig {
        let slug = "relay-\(index)"
        return RelayConfig(
            id: slug,
            name: index == 1 ? "shrimp-3" : slug,
            listenMode: .localhost,
            bridgePort: 18789 + index,
            remoteGatewayWSS: index == 1
                ? "wss://gcnxj886r06f-app_4jqhmcy1aypwy-1859632864877715.aiforce.run/af/openclaw"
                : "wss://example-team-app_xxx.aiforce.run/af/openclaw",
            gatewayToken: "SET_ME",
            browserProfileDir: NSString(string: "~/Library/Application Support/ShrimpMenubar/browser-profile-\(slug)").expandingTildeInPath,
            browserChannel: "chromium",
            relayScriptPath: "/Users/ai/clawd/workspaces/Leadership/tools/shrimp_relay.mjs",
            bridgeScriptPath: "/Users/ai/clawd/workspaces/Leadership/tools/shrimp_ws_bridge.mjs",
            nodeBinary: "node",
            loginLogPath: NSString(string: "~/.openclaw/logs/\(slug)-login/out.log").expandingTildeInPath,
            bridgeLogPath: NSString(string: "~/.openclaw/logs/\(slug)-bridge/out.log").expandingTildeInPath
        )
    }
}

struct AppSettings: Codable, Equatable {
    var relays: [RelayConfig]

    static let `default` = AppSettings(relays: [.template(index: 1)])
}

private struct LegacySingleConfig: Codable {
    var listenMode: ListenMode
    var bridgePort: Int
    var missionControlPort: Int?
    var overviewURL: String?
    var remoteGatewayWSS: String
    var gatewayToken: String
    var browserProfileDir: String
    var browserChannel: String
    var relayScriptPath: String
    var bridgeScriptPath: String
    var missionControlPath: String?
    var nodeBinary: String
    var npmBinary: String?
    var loginLogPath: String
    var bridgeLogPath: String
    var missionControlLogPath: String?
}

struct RelayStatusProbe: Codable {
    let summary: String
    let navigationError: String?
    let currentUrl: String?
}

@MainActor
final class RelayRuntime: ObservableObject {
    enum LoginState: String {
        case unknown
        case checking
        case loginRequired = "login_required"
        case loggedIn = "logged_in"
        case waitingUserLogin = "waiting_user_login"
        case failed
    }

    let id: String
    @Published var config: RelayConfig
    @Published var loginState: LoginState = .unknown
    @Published var bridgeRunning = false
    @Published var loginHelperRunning = false
    @Published var lastError: String?

    private var loginProcess: Process?
    private var bridgeProcess: Process?

    init(config: RelayConfig, warning: String? = nil) {
        self.id = config.id
        self.config = config
        self.lastError = warning
        ensureDirectories()
        applyConfigWarnings()
    }

    var name: String { config.name }
    var listenModeText: String { config.listenMode.title }
    var loginStateText: String {
        switch loginState {
        case .unknown: return "unknown"
        case .checking: return "checking"
        case .loginRequired: return "login required"
        case .loggedIn: return "logged in"
        case .waitingUserLogin: return "waiting user"
        case .failed: return "failed"
        }
    }
    var bridgeStateText: String { bridgeRunning ? "running" : "stopped" }
    var loginHelperStateText: String { loginHelperRunning ? "running" : "stopped" }
    var localRelayURL: String { "ws://127.0.0.1:\(config.bridgePort)" }
    var relayBindHost: String { config.listenMode.bindHost }

    var statusIconName: String {
        if bridgeRunning { return "checkmark.circle.fill" }
        switch loginState {
        case .loginRequired: return "person.crop.circle.badge.exclamationmark"
        case .loggedIn: return "person.crop.circle.badge.checkmark"
        case .waitingUserLogin, .checking: return "person.crop.circle.badge.clock"
        case .failed: return "xmark.circle"
        case .unknown: return "circle.dashed"
        }
    }

    var summaryLine: String {
        if bridgeRunning { return "Relay running at \(localRelayURL)" }
        switch loginState {
        case .loggedIn:
            return "Logged in. Ready to start relay."
        case .loginRequired:
            return "Feishu login required"
        case .waitingUserLogin:
            return "Login flow started in persistent browser profile."
        case .checking:
            return "Checking login state"
        case .failed:
            return "Login check failed"
        case .unknown:
            return "Configure WSS, token, browser profile, and port."
        }
    }

    var teamFeishuURL: String? {
        guard
            let host = URL(string: config.remoteGatewayWSS.trimmingCharacters(in: .whitespacesAndNewlines))?.host,
            let rawTeam = host.split(separator: "-").first,
            !rawTeam.isEmpty
        else { return nil }
        return "https://\(rawTeam).feishu.cn"
    }

    var derivedOverviewURL: String? {
        let trimmed = config.remoteGatewayWSS.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return nil }
        if components.scheme == "wss" { components.scheme = "https" }
        if components.scheme == "ws" { components.scheme = "http" }
        components.path = "/af/openclaw/overview"
        components.query = nil
        return components.url?.absoluteString
    }

    func updateConfig(_ newConfig: RelayConfig) {
        let wasBridgeRunning = bridgeProcess?.isRunning == true
        let wasLoginRunning = loginProcess?.isRunning == true
        stopAll()
        config = newConfig
        ensureDirectories()
        applyConfigWarnings()
        if wasBridgeRunning || wasLoginRunning {
            lastError = "Config updated. Restart relay/login flow manually so the new settings take effect."
        }
    }

    func startAll() {
        Task {
            applyConfigWarnings()
            await checkLoginState()
            guard loginState == .loggedIn else {
                openFeishuLogin()
                return
            }
            startRelay()
        }
    }

    func stopAll() {
        stopProcess(&bridgeProcess)
        stopProcess(&loginProcess)
        bridgeRunning = false
        loginHelperRunning = false
    }

    func checkLoginState() async {
        guard validateConfigForLogin() else { return }
        loginState = .checking
        lastError = nil
        do {
            let output = try await runToolCapture(
                currentDirectory: scriptDirectory(for: config.relayScriptPath),
                executable: "/usr/bin/env",
                arguments: [config.nodeBinary, config.relayScriptPath, "status", "1", "--with-token"],
                environment: shrimpEnvironment(targetURL: derivedOverviewURL ?? "")
            )
            let probe = try JSONDecoder().decode(RelayStatusProbe.self, from: Data(output.utf8))
            switch probe.summary {
            case "overview_loaded":
                loginState = .loggedIn
                lastError = nil
            case "sso_required":
                loginState = .loginRequired
                lastError = probe.navigationError
            default:
                loginState = .failed
                lastError = probe.navigationError ?? "Unexpected relay status: \(probe.summary)"
            }
        } catch {
            loginState = .failed
            lastError = "Login check failed: \(error.localizedDescription)"
        }
    }

    func openFeishuLogin() {
        guard validateConfigForLogin() else { return }
        guard let loginURL = teamFeishuURL else {
            lastError = "Could not derive team Feishu URL from Remote WSS."
            return
        }
        stopProcess(&loginProcess)
        do {
            let process = try launchProcess(
                currentDirectory: scriptDirectory(for: config.relayScriptPath),
                executable: "/usr/bin/env",
                arguments: [config.nodeBinary, config.relayScriptPath, "open", "1", "--hold"],
                environment: shrimpEnvironment(targetURL: loginURL),
                logPath: config.loginLogPath,
                onTerminate: { [weak self] in
                    Task { @MainActor in self?.loginHelperRunning = false }
                }
            )
            loginProcess = process
            loginHelperRunning = true
            loginState = .waitingUserLogin
            lastError = nil
        } catch {
            lastError = "Failed to open Feishu login window: \(error.localizedDescription)"
        }
    }

    func stopLoginHelper() {
        stopProcess(&loginProcess)
        loginHelperRunning = false
    }

    func startRelay() {
        guard validateConfigForRelay() else { return }
        stopProcess(&loginProcess)
        loginHelperRunning = false
        stopProcess(&bridgeProcess)
        do {
            let process = try launchProcess(
                currentDirectory: scriptDirectory(for: config.bridgeScriptPath),
                executable: "/usr/bin/env",
                arguments: [config.nodeBinary, config.bridgeScriptPath, "bridge", "1", "--host", relayBindHost, "--port", String(config.bridgePort)],
                environment: shrimpEnvironment(targetURL: derivedOverviewURL ?? ""),
                logPath: config.bridgeLogPath,
                onTerminate: { [weak self] in
                    Task { @MainActor in self?.bridgeRunning = false }
                }
            )
            bridgeProcess = process
            bridgeRunning = true
            Task {
                let ready = await waitForLocalTCPServer(host: "127.0.0.1", port: config.bridgePort, timeout: 8)
                if !ready {
                    lastError = "Relay did not become reachable on 127.0.0.1:\(config.bridgePort) within 8s. Check bridge log."
                }
            }
        } catch {
            lastError = "Relay failed to start: \(error.localizedDescription)"
        }
    }

    func stopRelay() {
        stopProcess(&bridgeProcess)
        bridgeRunning = false
    }

    func openTeamFeishu() {
        if let url = teamFeishuURL { openURL(url) }
    }

    func openOverview() {
        if let url = derivedOverviewURL { openURL(url) }
    }

    func openLoginLog() { NSWorkspace.shared.open(URL(fileURLWithPath: config.loginLogPath)) }
    func openBridgeLog() { NSWorkspace.shared.open(URL(fileURLWithPath: config.bridgeLogPath)) }

    private func validateConfigForLogin() -> Bool {
        let issues = configIssues(requireRemoteWSS: false)
        guard issues.isEmpty else {
            lastError = issues.joined(separator: "\n")
            return false
        }
        return true
    }

    private func validateConfigForRelay() -> Bool {
        let issues = configIssues(requireRemoteWSS: true)
        guard issues.isEmpty else {
            lastError = issues.joined(separator: "\n")
            return false
        }
        return true
    }

    private func applyConfigWarnings() {
        let issues = configIssues(requireRemoteWSS: false)
        if !issues.isEmpty {
            lastError = issues.joined(separator: "\n")
        }
    }

    private func configIssues(requireRemoteWSS: Bool) -> [String] {
        var issues: [String] = []

        if derivedOverviewURL?.isEmpty != false {
            issues.append("Set a valid Remote WSS first.")
        }

        if teamFeishuURL == nil {
            issues.append("Could not derive team Feishu URL from Remote WSS.")
        }

        let token = config.gatewayToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if token.isEmpty || token == "SET_ME" {
            issues.append("Set a real Gateway Token (current value is placeholder).")
        }

        if requireRemoteWSS && config.remoteGatewayWSS.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append("Set Remote WSS before starting the relay.")
        }

        if !FileManager.default.fileExists(atPath: config.relayScriptPath) {
            issues.append("Relay script not found: \(config.relayScriptPath)")
        }

        if !FileManager.default.fileExists(atPath: config.bridgeScriptPath) {
            issues.append("Bridge script not found: \(config.bridgeScriptPath)")
        }

        if !commandExists(config.nodeBinary) {
            issues.append("node binary not found in PATH: \(config.nodeBinary)")
        }

        return issues
    }

    private func shrimpEnvironment(targetURL: String) -> [String: String] {
        var env = ProcessInfo.processInfo.environment
        env["SHRIMP_PROFILE_DIR"] = config.browserProfileDir
        env["SHRIMP_BROWSER_CHANNEL"] = config.browserChannel
        env["SHRIMP_1_NAME"] = config.name
        env["SHRIMP_1_OVERVIEW_URL"] = targetURL
        env["SHRIMP_1_TOKEN"] = config.gatewayToken
        return env
    }

    private func ensureDirectories() {
        let fm = FileManager.default
        try? fm.createDirectory(atPath: config.browserProfileDir, withIntermediateDirectories: true)
        [config.loginLogPath, config.bridgeLogPath].forEach { path in
            let dir = (path as NSString).deletingLastPathComponent
            try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true)
            if !fm.fileExists(atPath: path) {
                fm.createFile(atPath: path, contents: nil)
            }
        }
    }

    private func scriptDirectory(for path: String) -> String {
        URL(fileURLWithPath: path).deletingLastPathComponent().path
    }

    private func launchProcess(
        currentDirectory: String,
        executable: String,
        arguments: [String],
        environment: [String: String],
        logPath: String,
        onTerminate: @escaping @Sendable () -> Void
    ) throws -> Process {
        ensureDirectories()
        let process = Process()
        process.currentDirectoryURL = URL(fileURLWithPath: currentDirectory)
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.environment = environment
        let fileHandle = try openAppendHandle(at: logPath)
        process.standardOutput = fileHandle
        process.standardError = fileHandle
        process.terminationHandler = { _ in
            try? fileHandle.close()
            onTerminate()
        }
        try process.run()
        return process
    }

    private func openAppendHandle(at path: String) throws -> FileHandle {
        let fm = FileManager.default
        if !fm.fileExists(atPath: path) {
            fm.createFile(atPath: path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: URL(fileURLWithPath: path))
        try handle.seekToEnd()
        return handle
    }

    private func runToolCapture(
        currentDirectory: String,
        executable: String,
        arguments: [String],
        environment: [String: String]
    ) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            let process = Process()
            let stdout = Pipe()
            let stderr = Pipe()
            process.currentDirectoryURL = URL(fileURLWithPath: currentDirectory)
            process.executableURL = URL(fileURLWithPath: executable)
            process.arguments = arguments
            process.environment = environment
            process.standardOutput = stdout
            process.standardError = stderr
            process.terminationHandler = { process in
                let out = String(data: stdout.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
                let err = String(data: stderr.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
                if process.terminationStatus == 0 {
                    continuation.resume(returning: out.trimmingCharacters(in: .whitespacesAndNewlines))
                } else {
                    let message = err.isEmpty ? out : err
                    continuation.resume(throwing: NSError(domain: "ShrimpMenubar", code: Int(process.terminationStatus), userInfo: [NSLocalizedDescriptionKey: message]))
                }
            }
            do {
                try process.run()
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    private func waitForLocalTCPServer(host: String, port: Int, timeout: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if canConnect(host: host, port: port) {
                return true
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
        return false
    }

    private func canConnect(host: String, port: Int) -> Bool {
        let socketFD = socket(AF_INET, SOCK_STREAM, 0)
        guard socketFD >= 0 else { return false }
        defer { close(socketFD) }

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.stride)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(UInt16(port).bigEndian)

        let result = host.withCString { cs in
            inet_pton(AF_INET, cs, &address.sin_addr)
        }
        guard result == 1 else { return false }

        var tv = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(socketFD, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(socketFD, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = address
        let connectResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in>.stride))
            }
        }
        return connectResult == 0
    }

    private func commandExists(_ command: String) -> Bool {
        if command.contains("/") {
            return FileManager.default.isExecutableFile(atPath: command)
        }

        let pathEntries = (ProcessInfo.processInfo.environment["PATH"] ?? "")
            .split(separator: ":")
            .map(String.init)

        for entry in pathEntries {
            let candidate = URL(fileURLWithPath: entry).appendingPathComponent(command).path
            if FileManager.default.isExecutableFile(atPath: candidate) {
                return true
            }
        }
        return false
    }

    private func stopProcess(_ process: inout Process?) {
        guard let proc = process else { return }
        if proc.isRunning {
            proc.terminate()
            DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) {
                if proc.isRunning { proc.interrupt() }
                DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) {
                    if proc.isRunning { kill(proc.processIdentifier, SIGKILL) }
                }
            }
        }
        process = nil
    }

    private func openURL(_ raw: String) {
        guard let url = URL(string: raw) else { return }
        NSWorkspace.shared.open(url)
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var relays: [RelayRuntime] = []
    @Published var globalWarning: String?

    private let configURL: URL

    init() {
        let fm = FileManager.default
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("ShrimpMenubar", isDirectory: true)
        try? fm.createDirectory(at: base, withIntermediateDirectories: true)
        configURL = base.appendingPathComponent("config.json")

        let loadResult = Self.loadSettings(at: configURL)
        globalWarning = loadResult.warning
        relays = loadResult.settings.relays.map { RelayRuntime(config: $0) }
    }

    var menuTitle: String {
        if relays.contains(where: { $0.bridgeRunning }) { return "Shrimp ✓" }
        if relays.contains(where: { $0.loginHelperRunning || $0.loginState == .checking }) { return "Shrimp …" }
        return "Shrimp"
    }

    var statusIconName: String {
        if relays.contains(where: { $0.bridgeRunning }) { return "checkmark.circle.fill" }
        if relays.contains(where: { $0.loginHelperRunning || $0.loginState == .checking }) { return "person.crop.circle.badge.clock" }
        if relays.contains(where: { $0.loginState == .loginRequired }) { return "person.crop.circle.badge.exclamationmark" }
        return "circle.dashed"
    }

    func saveConfigs(_ newConfigs: [RelayConfig]) {
        let cleaned = newConfigs.filter { !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let finalConfigs = cleaned.isEmpty ? [RelayConfig.template(index: 1)] : cleaned
        stopAll()
        do {
            let settings = AppSettings(relays: finalConfigs)
            let data = try JSONEncoder.pretty.encode(settings)
            try data.write(to: configURL, options: .atomic)
            globalWarning = nil
            relays = finalConfigs.map { RelayRuntime(config: $0) }
        } catch {
            globalWarning = "Failed to save config: \(error.localizedDescription)"
        }
    }

    func makeNewRelayTemplate(index: Int) -> RelayConfig {
        let safeIndex = max(1, index)
        let unique = relays.map(\.config.bridgePort).max().map { $0 + 1 } ?? (18789 + safeIndex)
        var item = RelayConfig.template(index: safeIndex)
        item.id = "relay-\(UUID().uuidString.prefix(8))"
        item.name = "relay-\(safeIndex)"
        item.bridgePort = unique
        item.browserProfileDir = NSString(string: "~/Library/Application Support/ShrimpMenubar/browser-profile-\(item.id)").expandingTildeInPath
        item.loginLogPath = NSString(string: "~/.openclaw/logs/\(item.id)-login/out.log").expandingTildeInPath
        item.bridgeLogPath = NSString(string: "~/.openclaw/logs/\(item.id)-bridge/out.log").expandingTildeInPath
        return item
    }

    func stopAll() {
        relays.forEach { $0.stopAll() }
    }

    func openConfig() { NSWorkspace.shared.open(configURL) }

    private static func loadSettings(at url: URL) -> (settings: AppSettings, warning: String?) {
        if let data = try? Data(contentsOf: url) {
            do {
                let decoded = try JSONDecoder().decode(AppSettings.self, from: data)
                if decoded.relays.isEmpty {
                    let settings = AppSettings.default
                    if let replacement = try? JSONEncoder.pretty.encode(settings) {
                        try? replacement.write(to: url, options: .atomic)
                    }
                    return (settings, "Config had zero relays; rewrote a default configuration.")
                }
                return (decoded, nil)
            } catch {
                if let legacy = try? JSONDecoder().decode(LegacySingleConfig.self, from: data) {
                    let migrated = AppSettings(relays: [RelayConfig(
                        id: "relay-1",
                        name: "shrimp-1",
                        listenMode: legacy.listenMode,
                        bridgePort: legacy.bridgePort,
                        remoteGatewayWSS: legacy.remoteGatewayWSS,
                        gatewayToken: legacy.gatewayToken,
                        browserProfileDir: legacy.browserProfileDir,
                        browserChannel: legacy.browserChannel,
                        relayScriptPath: legacy.relayScriptPath,
                        bridgeScriptPath: legacy.bridgeScriptPath,
                        nodeBinary: legacy.nodeBinary,
                        loginLogPath: legacy.loginLogPath,
                        bridgeLogPath: legacy.bridgeLogPath
                    )])
                    if let replacement = try? JSONEncoder.pretty.encode(migrated) {
                        try? replacement.write(to: url, options: .atomic)
                    }
                    return (migrated, "Migrated legacy single-relay config to multi-relay format.")
                }

                let settings = AppSettings.default
                if let replacement = try? JSONEncoder.pretty.encode(settings) {
                    try? replacement.write(to: url, options: .atomic)
                }
                return (settings, "Config decode failed; rewrote a fresh multi-relay config. Error: \(error.localizedDescription)")
            }
        }

        let settings = AppSettings.default
        if let data = try? JSONEncoder.pretty.encode(settings) {
            try? data.write(to: url, options: .atomic)
        }
        return (settings, nil)
    }
}

private extension JSONEncoder {
    static var pretty: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }
}
