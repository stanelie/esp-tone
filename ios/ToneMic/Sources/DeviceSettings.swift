import Foundation
import Darwin

/// Client for the device's self-describing settings protocol (SPEC.md §8.2).
///
/// Nothing here knows what settings exist, what values are legal, or when they take effect — the
/// device reports all of it and the UI is built from the reply. That is deliberate: every settings
/// bug in the old Android app came from duplicating constraints the firmware owns. The device even
/// names its own values, because a codec dropdown reading "5" and "6" is useless and teaching the
/// app that 5 means ADPCM would be exactly the duplication this avoids.
///
///     request  GET_SETTINGS : E7 03 01
///     reply    SETTINGS     : E7 03 02 <count>, then per setting:
///                               id u8, kind u8 (1=enum, 2=range), applicable u8,
///                               applies_when u8 (0=now, 1=stream restart, 2=reboot),
///                               current u32le, n u8, values[n] u32le,
///                               n x (vlabel_len u8, vlabel), label_len u8, label
///     request  SET_SETTING  : E7 03 03 <id> <value u32le>
///     reply    SET_RESULT   : E7 03 04 <id> <status u8> <value u32le>
struct DeviceSetting: Identifiable, Equatable {
    let id: Int
    let kind: Int                 // 1 = enum, 2 = range
    let applicable: Bool
    let appliesWhen: Int
    let current: UInt32
    let values: [UInt32]
    let valueLabels: [String]
    let label: String

    var isEnum: Bool { kind == 1 }

    /// What the device calls `v`, falling back to the raw number.
    func text(for v: UInt32) -> String {
        guard let i = values.firstIndex(of: v), i < valueLabels.count else { return String(v) }
        return valueLabels[i]
    }

    var whenText: String {
        switch appliesWhen {
        case 0: return "applies immediately"
        case 1: return "applies when playback restarts"
        case 2: return "applies after a reboot"
        default: return ""
        }
    }
}

enum DeviceSettings {
    private static let m0: UInt8 = 0xE7, m1: UInt8 = 0x03
    private static let msgGet: UInt8 = 1, msgSettings: UInt8 = 2
    private static let msgSet: UInt8 = 3, msgResult: UInt8 = 4

    static func statusText(_ s: Int) -> String {
        switch s {
        case 0: return "ok"
        case 1: return "the device does not know that setting"
        case 2: return "the device does not allow that value"
        case 3: return "not applicable right now"
        default: return "unknown status \(s)"
        }
    }

    /// Asks the device what it has. Nil means it never answered.
    static func fetch(host: String, timeout: TimeInterval = 2.5) -> [DeviceSetting]? {
        guard let d = exchange(host: host, request: Data([m0, m1, msgGet]),
                               expect: msgSettings, timeout: timeout) else { return nil }
        return parse(d)
    }

    /// Changes one setting. Returns the device's status and the value it ended up with.
    static func set(host: String, id: Int, value: UInt32,
                    timeout: TimeInterval = 5.0) -> (status: Int, value: UInt32)? {
        var req = Data([m0, m1, msgSet, UInt8(id)])
        req.append(contentsOf: [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF),
                                UInt8((value >> 16) & 0xFF), UInt8((value >> 24) & 0xFF)])
        guard let d = exchange(host: host, request: req, expect: msgResult, timeout: timeout),
              d.count >= 9 else { return nil }
        let b = d.startIndex
        return (Int(d[b + 4]), le32(d, b + 5))
    }

    // MARK: - wire

    /// Sends until something of the expected type comes back. A stream may be running, so other
    /// packets arrive on this socket too; they are skipped rather than treated as failures.
    private static func exchange(host: String, request: Data,
                                 expect: UInt8, timeout: TimeInterval) -> Data? {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var tv = timeval(tv_sec: 0, tv_usec: 400_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = Eassp.port.bigEndian
        guard inet_pton(AF_INET, host, &to.sin_addr) == 1 else { return nil }

        let bytes = [UInt8](request)
        let deadline = Date().addingTimeInterval(timeout)
        var nextSend = Date.distantPast

        while Date() < deadline {
            if Date() >= nextSend {
                nextSend = Date().addingTimeInterval(0.8)
                withUnsafePointer(to: &to) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        _ = sendto(fd, bytes, bytes.count, 0, sa,
                                   socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            var buf = [UInt8](repeating: 0, count: 1024)
            let n = recv(fd, &buf, buf.count, 0)
            guard n >= 3 else { continue }
            if buf[0] == m0, buf[1] == m1, buf[2] == expect {
                return Data(buf[0..<n])
            }
        }
        return nil
    }

    private static func parse(_ d: Data) -> [DeviceSetting] {
        var out: [DeviceSetting] = []
        let b = d.startIndex
        guard d.count >= 4 else { return out }
        var o = b + 4
        for _ in 0..<Int(d[b + 3]) {
            guard o + 10 <= d.endIndex else { return out }
            let id = Int(d[o]), kind = Int(d[o + 1])
            let applicable = d[o + 2] != 0, when = Int(d[o + 3])
            o += 4
            let current = le32(d, o); o += 4
            let n = Int(d[o]); o += 1
            guard o + 4 * n + 1 <= d.endIndex else { return out }
            var values = [UInt32]()
            for _ in 0..<n { values.append(le32(d, o)); o += 4 }
            var labels = [String]()
            for _ in 0..<n {
                guard o < d.endIndex else { return out }
                let l = Int(d[o]); o += 1
                guard o + l <= d.endIndex else { return out }
                labels.append(String(decoding: d[o..<(o + l)], as: UTF8.self)); o += l
            }
            guard o < d.endIndex else { return out }
            let ll = Int(d[o]); o += 1
            guard o + ll <= d.endIndex else { return out }
            let label = String(decoding: d[o..<(o + ll)], as: UTF8.self); o += ll
            out.append(DeviceSetting(id: id, kind: kind, applicable: applicable,
                                     appliesWhen: when, current: current, values: values,
                                     valueLabels: labels, label: label))
        }
        return out
    }

    private static func le32(_ d: Data, _ o: Int) -> UInt32 {
        UInt32(d[o]) | (UInt32(d[o+1]) << 8) | (UInt32(d[o+2]) << 16) | (UInt32(d[o+3]) << 24)
    }
}
