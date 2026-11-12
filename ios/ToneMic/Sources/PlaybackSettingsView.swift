import SwiftUI

/// Everything that happens on this phone rather than on the microphone. Behind a button for the
/// same reason the device settings are: they are set once and then left alone, so they should not
/// cost screen space on the screen you actually use.
struct PlaybackSettingsView: View {
    @ObservedObject var client: MicClient
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Stepper("Gain  \(client.gainDb) dB", value: $client.gainDb, in: 0...42, step: 6)
                    Toggle("Limiter (AGC)", isOn: $client.agcOn)
                } footer: {
                    Text("The limiter ducks before a loud sound overflows and recovers in about "
                         + "0.15 s. It adds 2 ms of delay.")
                }

                Section {
                    Picker("Jitter buffer", selection: $client.bufferMs) {
                        Text("Auto").tag(0)
                        ForEach([40, 80, 120, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000],
                                id: \.self) {
                            Text($0 < 1000 ? "\($0) ms" : String(format: "%.1f s", Double($0) / 1000))
                                .tag($0)
                        }
                    }
                } footer: {
                    Text("Lower is less delay, higher rides out more network jitter. Applies "
                         + "immediately. Auto follows the worst gaps seen in the last 10 seconds, "
                         + "up to 5 s — enough for a poor WAN link.")
                }

                Section {
                    Picker("Low cut", selection: $client.lowCutHz) {
                        ForEach([0, 50, 80, 120, 160, 250, 400, 600], id: \.self) {
                            Text($0 == 0 ? "Off" : "\($0) Hz").tag($0)
                        }
                    }
                    Picker("High cut", selection: $client.highCutHz) {
                        ForEach([0, 2000, 3000, 4000, 6000, 8000, 10000, 12000, 15000], id: \.self) {
                            Text($0 == 0 ? "Off" : "\($0 / 1000) kHz").tag($0)
                        }
                    }
                } footer: {
                    Text("12 dB/octave, applied here only — what the microphone captures and "
                         + "sends is untouched.")
                }

                Section("Diagnostics") {
                    Text(client.prefsDebug.isEmpty ? "—" : client.prefsDebug)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Local playback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
