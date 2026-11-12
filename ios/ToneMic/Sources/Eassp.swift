import Foundation

/// EASSP, the microphone's protocol (SPEC.md §4.7). Control is UDP 3950; audio arrives on a port
/// we nominate with CONFIGURE. Multi-byte fields are little-endian except CONFIGURE's port.
enum Eassp {
    static let port: UInt16 = 3950

    static let cmdDiscover: UInt8 = 0x01
    static let cmdConfigure: UInt8 = 0x02
    static let cmdStop: UInt8 = 0x03
    static let cmdInfo: UInt8 = 0x81

    static let codecADPCM: UInt8 = 5
    static let codecPCM: UInt8 = 6

    struct Info {
        let id: String          // MAC, the stable identity
        let name: String
        let status: UInt8
        let codec: UInt8
        let channels: Int
        let rate: Int
        let bits: Int
    }

    static func packet(_ cmd: UInt8, seq: UInt16, payload: [UInt8] = []) -> Data {
        var b: [UInt8] = [0xEA, 0x55, 1, cmd,
                          UInt8(seq & 0xFF), UInt8(seq >> 8),
                          UInt8(payload.count & 0xFF), UInt8(payload.count >> 8)]
        b.append(contentsOf: payload)
        return Data(b)
    }

    /// The device starts sending audio to `streamPort`. That port is the one big-endian field.
    static func configure(seq: UInt16, streamPort: UInt16) -> Data {
        packet(cmdConfigure, seq: seq, payload: [UInt8(streamPort >> 8), UInt8(streamPort & 0xFF)])
    }

    /// Repeated while streaming: the device drops a listener that goes quiet (SPEC.md §10).
    static func heartbeat(seq: UInt16, received: UInt32) -> Data {
        packet(cmdDiscover, seq: seq, payload: [
            UInt8(received & 0xFF), UInt8((received >> 8) & 0xFF),
            UInt8((received >> 16) & 0xFF), UInt8((received >> 24) & 0xFF),
        ])
    }

    /// The sequence number an INFO echoes back, which is what matches a reply to its request.
    static func infoSeq(_ d: Data) -> UInt16? {
        guard d.count >= 8, d[d.startIndex] == 0xEA, d[d.startIndex + 1] == 0x55,
              d[d.startIndex + 3] == cmdInfo else { return nil }
        return UInt16(d[d.startIndex + 4]) | (UInt16(d[d.startIndex + 5]) << 8)
    }

    static func parseInfo(_ d: Data) -> Info? {
        guard d.count >= 8 + 34, d[0] == 0xEA, d[1] == 0x55, d[2] == 1,
              d[3] == cmdInfo else { return nil }
        let p = 8
        let mac = (0..<6).map { String(format: "%02x", d[p + 9 + $0]) }.joined()
        var name = ""
        if d.count >= p + 58 {
            let raw = d.subdata(in: (p + 34)..<(p + 58))
            name = String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
                .trimmingCharacters(in: .whitespaces)
        }
        return Info(id: mac,
                    name: name.isEmpty ? "esp-mic" : name,
                    status: d[p],
                    codec: d[p + 1],
                    channels: Int(d[p + 3]),
                    rate: Int(le32(d, p + 4)),
                    bits: Int(d[p + 32]))
    }

    /// The audio header: seq LE16 at 0, codec at 6, rate enum at 7, channels at 8, bits LE16 at 14.
    struct AudioHeader {
        let seq: UInt16, codec: UInt8, rate: Int, channels: Int, bits: Int
        static let size = 16
    }

    static func parseAudio(_ d: Data) -> AudioHeader? {
        guard d.count > AudioHeader.size else { return nil }
        return AudioHeader(seq: UInt16(d[0]) | (UInt16(d[1]) << 8),
                           codec: d[6],
                           rate: rateFromEnum(d[7]),
                           channels: max(1, min(2, Int(d[8]))),
                           bits: Int(d[14]) | (Int(d[15]) << 8))
    }

    static func rateFromEnum(_ e: UInt8) -> Int {
        switch e {
        case 0: return 8000;  case 1: return 11025; case 2: return 16000
        case 3: return 22050; case 4: return 32000; case 5: return 44100
        default: return 48000
        }
    }

    /// The rate the ESP really samples at: its I2S clock is 160 MHz through two integer dividers,
    /// so a nominal 44100 is really 43860 (SPEC.md §9.5). The EASSP header carries only the
    /// nominal enum, so the receiver has to reproduce the same search or the pitch is wrong.
    static func actualRate(nominal: Int, bits: Int) -> Int {
        let base = 160_000_000.0 / (bits == 16 ? 32.0 : 48.0)
        var best = Double.greatestFiniteMagnitude
        var rate = Double(nominal)
        for i in 1..<64 {
            for j in i..<64 {
                let r = base / Double(i) / Double(j)
                let d = abs(r - Double(nominal))
                if d < best { best = d; rate = r }
            }
        }
        return Int(rate.rounded())
    }

    static func le32(_ d: Data, _ o: Int) -> UInt32 {
        UInt32(d[o]) | (UInt32(d[o+1]) << 8) | (UInt32(d[o+2]) << 16) | (UInt32(d[o+3]) << 24)
    }
}
