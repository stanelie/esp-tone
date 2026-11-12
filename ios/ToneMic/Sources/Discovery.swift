import Foundation
import Darwin

struct Found: Identifiable, Equatable {
    let id: String          // the MAC: stable across address changes
    let name: String
    let host: String
}

/// Finds microphones by sweeping the attached subnet with unicast EASSP DISCOVER.
///
/// The Android client also broadcasts, which is faster, but on iOS broadcast and multicast are
/// gated behind `com.apple.developer.networking.multicast` — an entitlement Apple grants by
/// request. Unicast needs nothing beyond the local-network permission the app already holds, and
/// it reaches the same devices; it just costs one datagram per address.
///
/// One socket sends to every host and collects whatever answers, rather than a socket (or a
/// connection) per address: a few hundred datagrams out, replies gathered for a window.
enum Discovery {

    /// Matches the Android scan: the real prefix, never wider than a /20.
    private static let minPrefix: UInt32 = 20
    private static let windowSeconds = 3.0
    private static let resendSeconds = 1.2

    static func scan() -> [Found] {
        guard let link = attachedIPv4() else {
            print("[ToneMic] scan: no usable IPv4 interface")
            return []
        }
        let mask = UInt32.max << (32 - link.prefix)
        let net = link.address & mask
        let broadcast = net | ~mask
        print("[ToneMic] scan: \(link.name) \(dotted(link.address))/\(link.prefix), "
              + "\(max(0, Int(broadcast) - Int(net) - 1)) addresses")
        guard broadcast > net + 1 else {
            print("[ToneMic] scan: no host range on that interface")
            return []
        }

        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return [] }
        defer { close(fd) }

        var tv = timeval(tv_sec: 0, tv_usec: 200_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        let query = [UInt8](Eassp.packet(Eassp.cmdDiscover, seq: 0))
        var found = [String: Found]()
        let deadline = Date().addingTimeInterval(windowSeconds)
        var nextSend = Date.distantPast

        while Date() < deadline {
            if Date() >= nextSend {
                nextSend = Date().addingTimeInterval(resendSeconds)
                var sent = 0
                for a in (net + 1)..<broadcast where a != link.address {
                    var to = sockaddr_in()
                    to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                    to.sin_family = sa_family_t(AF_INET)
                    to.sin_port = Eassp.port.bigEndian
                    to.sin_addr.s_addr = a.bigEndian
                    withUnsafePointer(to: &to) {
                        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                            _ = sendto(fd, query, query.count, 0, sa,
                                       socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                    // A few hundred datagrams at once can exhaust the send buffer; pace them.
                    sent += 1
                    if sent % 64 == 0 { usleep(2000) }
                }
            }

            var buf = [UInt8](repeating: 0, count: 512)
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n: Int = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(fd, &buf, buf.count, 0, sa, &len)
                }
            }
            guard n > 0 else { continue }      // timeout: go round, maybe resend
            let data = Data(buf[0..<n])
            guard let info = Eassp.parseInfo(data) else { continue }
            let host = dotted(UInt32(bigEndian: from.sin_addr.s_addr))
            found[info.id] = Found(id: info.id, name: info.name, host: host)
        }
        return found.values.sorted { $0.name < $1.name }
    }

    /// Asks one address directly. Used to check a remembered device before a full sweep, and to
    /// confirm a hand-typed address — a routed or VPN host is never found by the subnet sweep but
    /// answers this perfectly well.
    static func probe(host: String, timeout: TimeInterval = 1.5) -> Eassp.Info? {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var tv = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = Eassp.port.bigEndian
        guard inet_pton(AF_INET, host, &to.sin_addr) == 1 else { return nil }

        let query = [UInt8](Eassp.packet(Eassp.cmdDiscover, seq: 0))
        let deadline = Date().addingTimeInterval(timeout)
        var nextSend = Date.distantPast
        while Date() < deadline {
            if Date() >= nextSend {
                nextSend = Date().addingTimeInterval(0.5)
                withUnsafePointer(to: &to) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        _ = sendto(fd, query, query.count, 0, sa,
                                   socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            var buf = [UInt8](repeating: 0, count: 512)
            let n = recv(fd, &buf, buf.count, 0)
            if n > 0, let info = Eassp.parseInfo(Data(buf[0..<n])) { return info }
        }
        return nil
    }

    // MARK: - the attached network

    private struct Link { let name: String; let address: UInt32; let prefix: UInt32 }

    /// Our own IPv4 address and prefix on the interface that actually carries LAN traffic.
    ///
    /// Picking "the first non-loopback IPv4 interface that is up" is wrong on iOS: the list also
    /// holds VPN tunnels (`utun*`), cellular (`pdp_ip0`), AirDrop's `awdl0` and link-local
    /// addresses. A tunnel with a /32 mask yields net == broadcast, so the sweep has no addresses
    /// to visit and returns instantly having sent nothing — which is exactly how this failed.
    /// Wi-Fi (`en*`) is what reaches the microphone, so prefer it and require a usable prefix.
    private static func attachedIPv4() -> Link? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var candidates: [Link] = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let f = p.pointee
            guard let sa = f.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let flags = Int32(f.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            guard let maskPtr = f.ifa_netmask else { continue }
            let name = String(cString: f.ifa_name)

            let addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            let mask = maskPtr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            let real = UInt32(mask.nonzeroBitCount)
            guard addr != 0, real < 31 else { continue }        // /31 and /32 have no host range
            guard (addr >> 16) != 0xA9FE else { continue }      // 169.254/16 link-local
            guard !name.hasPrefix("awdl"), !name.hasPrefix("llw") else { continue }
            candidates.append(Link(name: name, address: addr, prefix: max(minPrefix, real)))
        }
        if candidates.isEmpty { return nil }
        print("[ToneMic] interfaces: "
              + candidates.map { "\($0.name) \(dotted($0.address))/\($0.prefix)" }.joined(separator: ", "))
        // Wi-Fi first; anything else only if there is no Wi-Fi.
        return candidates.first { $0.name.hasPrefix("en") } ?? candidates.first
    }

    private static func dotted(_ a: UInt32) -> String {
        "\((a >> 24) & 255).\((a >> 16) & 255).\((a >> 8) & 255).\(a & 255)"
    }
}
