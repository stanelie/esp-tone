import Foundation

/// A transmitter the app remembers. `id` is the MAC from INFO: the stable identity, so a device
/// that moves to a new address is still the same device.
struct StoredDevice: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var host: String
    /// This is the one that was playing when the app was last used, so it is the one to resume.
    /// Set when playback starts and cleared when the user stops — not a user-facing switch, since
    /// there is only ever one and the app already knows which.
    var resume: Bool = false
}

/// Persists the device list in UserDefaults. Small enough that JSON in one key is the right
/// amount of machinery.
enum DeviceStore {
    private static let key = "devices"

    static func all() -> [StoredDevice] {
        guard let d = UserDefaults.standard.data(forKey: key),
              let list = try? JSONDecoder().decode([StoredDevice].self, from: d) else { return [] }
        return list
    }

    private static func write(_ list: [StoredDevice]) {
        if let d = try? JSONEncoder().encode(list) {
            UserDefaults.standard.set(d, forKey: key)
        }
    }

    /// Insert or update by id, keeping the resume flag unless told otherwise.
    static func save(_ device: StoredDevice) {
        var list = all()
        if let i = list.firstIndex(where: { $0.id == device.id }) {
            let keepResume = list[i].resume
            list[i] = device
            list[i].resume = device.resume || keepResume
        } else {
            list.append(device)
        }
        write(list)
    }

    static func remove(id: String) {
        write(all().filter { $0.id != id })
    }

    /// Marks one device as the one to resume, clearing the flag everywhere else. Nil clears all.
    static func setResume(_ id: String?) {
        write(all().map { var d = $0; d.resume = (d.id == id); return d })
    }

    static func resumeTarget() -> StoredDevice? {
        all().first { $0.resume }
    }

    /// Keeps the remembered address current when a known device turns up somewhere new.
    static func updateAddress(id: String, host: String) {
        var list = all()
        guard let i = list.firstIndex(where: { $0.id == id }), list[i].host != host else { return }
        list[i].host = host
        write(list)
    }
}
