// Task-owned AppKit surface for real Accessibility input, with an independent state file.
import AppKit

@MainActor final class AXTextFixture: NSObject, NSApplicationDelegate {
    let statePath: String
    var window: NSWindow!
    var field: NSTextField!
    var result: NSTextField!
    var submissions = 0
    var submittedValue = ""

    init(statePath: String) { self.statePath = statePath }

    func applicationDidFinishLaunching(_ notification: Notification) {
        window = NSWindow(contentRect: NSRect(x: 160, y: 180, width: 470, height: 230),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "FunnyWork AX Test Only"
        window.isReleasedWhenClosed = false
        let content = window.contentView!
        let title = NSTextField(labelWithString: "FunnyWork Accessibility test fixture")
        title.frame = NSRect(x: 24, y: 175, width: 420, height: 24)
        content.addSubview(title)
        field = NSTextField(frame: NSRect(x: 24, y: 125, width: 420, height: 30))
        field.setAccessibilityLabel("Fixture editable input")
        field.setAccessibilityIdentifier("funnywork-ax-input")
        content.addSubview(field)
        let submit = NSButton(title: "Submit fixture value", target: self, action: #selector(submitValue))
        submit.frame = NSRect(x: 24, y: 76, width: 210, height: 32)
        submit.setAccessibilityIdentifier("funnywork-ax-submit")
        content.addSubview(submit)
        result = NSTextField(labelWithString: "Result: awaiting submission")
        result.frame = NSRect(x: 24, y: 25, width: 420, height: 30)
        result.setAccessibilityLabel("Fixture submission result")
        content.addSubview(result)
        window.orderFront(nil)
        persist()
        print("READY")
        fflush(stdout)
    }

    @objc func submitValue() {
        submissions += 1
        submittedValue = field.stringValue
        result.stringValue = "Result: \(submittedValue)"
        persist()
    }

    func persist() {
        let state: [String: Any] = ["pid": ProcessInfo.processInfo.processIdentifier,
                                  "submissions": submissions, "submittedValue": submittedValue,
                                  "currentValue": field?.stringValue ?? ""]
        if let data = try? JSONSerialization.data(withJSONObject: state, options: [.sortedKeys]) {
            try? data.write(to: URL(fileURLWithPath: statePath), options: [.atomic])
        }
    }
}

MainActor.assumeIsolated {
    let application = NSApplication.shared
    application.setActivationPolicy(.regular)
    let delegate = AXTextFixture(statePath: CommandLine.arguments[1])
    application.delegate = delegate
    application.run()
}
