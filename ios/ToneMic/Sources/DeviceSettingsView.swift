import SwiftUI

/// Built entirely from the device's descriptor: the rows, the allowed values, their names and
/// whether each is usable right now all come off the wire. Adding a setting to the firmware makes
/// it appear here with no change to this file.
struct DeviceSettingsView: View {
    let host: String
    @Environment(\.dismiss) private var dismiss

    @State private var settings: [DeviceSetting] = []
    @State private var status = ""
    @State private var loading = false
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                if settings.isEmpty {
                    Section {
                        Text(loading ? "Reading settings from \(host)…"
                                     : "No settings read yet.")
                            .foregroundStyle(.secondary)
                    }
                }

                ForEach(settings) { s in
                    Section {
                        if s.isEnum {
                            Picker(s.label, selection: binding(for: s)) {
                                ForEach(s.values, id: \.self) { v in
                                    Text(s.text(for: v)).tag(v)
                                }
                            }
                            .disabled(!s.applicable || busy)
                        } else {
                            LabeledContent(s.label, value: s.text(for: s.current))
                        }
                    } footer: {
                        Text(s.applicable ? s.whenText : "not applicable right now")
                    }
                }

                Section {
                    Button("Reload") { load() }.disabled(loading || busy)
                    if !status.isEmpty {
                        Text(status).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Remote microphone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { if settings.isEmpty { load() } }
        }
    }

    private func binding(for s: DeviceSetting) -> Binding<UInt32> {
        Binding(get: { s.current }, set: { apply(s, $0) })
    }

    private func load() {
        loading = true
        status = ""
        Task.detached(priority: .userInitiated) {
            let got = DeviceSettings.fetch(host: host)
            await MainActor.run {
                loading = false
                if let got {
                    settings = got
                    status = "Read \(got.count) setting(s) from \(host)."
                } else {
                    status = "No answer from \(host)."
                }
            }
        }
    }

    /// The device decides. We send the request and then show whatever it reports back, rather than
    /// assuming the change took — a refusal has to be visible, not silently repainted.
    private func apply(_ s: DeviceSetting, _ v: UInt32) {
        guard v != s.current, !busy else { return }
        busy = true
        status = "Setting \(s.label) to \(s.text(for: v))…"
        Task.detached(priority: .userInitiated) {
            let result = DeviceSettings.set(host: host, id: s.id, value: v)
            let refreshed = DeviceSettings.fetch(host: host)
            await MainActor.run {
                busy = false
                if let refreshed { settings = refreshed }
                if let result {
                    status = result.status == 0
                        ? "\(s.label) is now \(s.text(for: result.value))."
                        : "Refused: \(DeviceSettings.statusText(result.status))."
                } else {
                    status = "No answer from \(host)."
                }
            }
        }
    }
}
