import Foundation

/// IMA ADPCM, DVI4 layout (RFC 3551), matching the firmware's encoder.
///
/// Each packet is self-contained: it opens with the predictor and step index it starts from, so a
/// lost packet costs exactly that packet and the next resynchronises (SPEC.md §14.1). That is why
/// this decoder keeps no state between calls.
enum Adpcm {
    private static let indexTable: [Int] = [-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8]
    private static let stepTable: [Int] = [
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,
        130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,
        1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,
        5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,
        27086,29794,32767]

    /// Decodes one block into 16-bit samples. Two samples per byte, high nibble first.
    static func decode(_ b: Data) -> [Int16] {
        guard b.count >= 4 else { return [] }
        let base = b.startIndex
        var predictor = Int(Int16(bitPattern: UInt16(b[base]) | (UInt16(b[base + 1]) << 8)))
        var index = max(0, min(88, Int(b[base + 2])))
        var out = [Int16]()
        out.reserveCapacity((b.count - 4) * 2)

        for k in (base + 4)..<b.endIndex {
            let byte = b[k]
            for code in [Int(byte >> 4), Int(byte & 0x0F)] {
                let step = stepTable[index]
                var diff = step >> 3
                if code & 4 != 0 { diff += step }
                if code & 2 != 0 { diff += step >> 1 }
                if code & 1 != 0 { diff += step >> 2 }
                predictor = (code & 8 != 0) ? predictor - diff : predictor + diff
                predictor = max(-32768, min(32767, predictor))
                index = max(0, min(88, index + indexTable[code]))
                out.append(Int16(predictor))
            }
        }
        return out
    }
}
