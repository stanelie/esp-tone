import SwiftUI

struct ContentView: View {
    @StateObject private var client = MicClient()
    @State private var showMicSettings = false
    @State private var showPlaybackSettings = false
    @State private var showAddByAddress = false
    @State private var manualHost = ""

    private var playing: Bool { client.state == .playing || client.state == .connecting }

    var body: some View {
        Form {
            // First thing on screen and first thing reachable by thumb: this is the only control
            // that gets used every session.
            Section {
                Button(playing ? "Stop" : "Play") {
                    playing ? client.stop() : client.start()
                }
                .frame(maxWidth: .infinity)
                .font(.title3.weight(.semibold))
                .buttonStyle(.borderedProminent)
                .tint(playing ? .red : .accentColor)
                .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))

                Text(client.status)
                    .font(.footnote.monospaced())
                    .foregroundStyle(statusColour)

                /* End-to-end delay, with the terms spelled out. The breakdown is the useful part:
                 * the total is dominated by the jitter buffer, so seeing "buffer 201" next to a
                 * 268 ms total says immediately that the delay is a buffering choice and not the
                 * network, the phone or the microphone. */
                if let l = client.latency {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Delay \(Int(l.totalMs.rounded())) ms end to end")
                            .font(.footnote.monospaced().weight(.semibold))
                        Text(String(format: "buffer %.0f · net %.1f · capture %.1f · output %.1f",
                                    l.bufferMs, l.networkMs, l.captureMs, l.outputMs))
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section {
                if client.rows.isEmpty {
                    Text(client.scanning ? "Scanning…" : "No transmitters remembered yet")
                        .foregroundStyle(.secondary)
                }
                ForEach(client.rows) { row in
                    deviceRow(row)
                }
                .onDelete { idx in
                    idx.map { client.rows[$0].device }.forEach(client.forget)
                }
                Button(client.scanning ? "Scanning…" : "Scan for transmitters") { client.scan() }
                    .disabled(client.scanning || playing)
                Button("Add by address…") {
                    manualHost = ""
                    showAddByAddress = true
                }
                .disabled(playing)
            } header: {
                Text("Transmitters")
            } footer: {
                Text("Green means it answered the last scan. Swipe a row to forget it.")
            }

            Section {
                Button("Local playback settings…") { showPlaybackSettings = true }
                Button("Remote microphone settings…") { showMicSettings = true }
                    .disabled(client.host.isEmpty)
            }

            Section {
                Text("Tone Mic")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity)
            }
            .listRowBackground(Color.clear)
        }
        .task { client.resumeIfWasPlaying() }
        .alert("Add by address", isPresented: $showAddByAddress) {
            TextField("10.8.1.168", text: $manualHost)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.numbersAndPunctuation)
            Button("Add") { client.addByAddress(manualHost) }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("The scan cannot cross a router, so a transmitter on another network — over a "
                 + "VPN, say — has to be given by address.")
        }
        .sheet(isPresented: $showMicSettings) {
            DeviceSettingsView(host: client.host)
        }
        .sheet(isPresented: $showPlaybackSettings) {
            PlaybackSettingsView(client: client)
        }
    }

    private func deviceRow(_ row: MicClient.Row) -> some View {
        Button {
            client.select(row.device)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.device.name)
                        .foregroundStyle(row.available ? Color.green : Color.secondary)
                    Text(row.device.host)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if row.device.host == client.host {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                }
            }
        }
        .disabled(playing)
    }

    private var statusColour: Color {
        switch client.state {
        case .playing: return .green
        case .failed:  return .red
        default:       return .secondary
        }
    }
}

#Preview { ContentView() }
