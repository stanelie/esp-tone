import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// iOS app icons must be square, 1024x1024 and fully opaque — alpha is rejected at submission and
// renders badly on the home screen. The clipart is portrait with a transparent background, so it
// is scaled to fit a margin and drawn onto a solid ground, not merely resized.
//
// CoreGraphics rather than AppKit: a command-line tool drawing through NSImage/NSGraphicsContext
// without an NSApplication crashes (SIGILL). ImageIO + CGContext work headless.
let args = CommandLine.arguments
guard args.count == 3 else { FileHandle.standardError.write(Data("usage: makeicon in out\n".utf8)); exit(1) }

guard let srcURL = URL(string: "file://" + args[1].addingPercentEncoding(
          withAllowedCharacters: .urlPathAllowed)!),
      let source = CGImageSourceCreateWithURL(srcURL as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    FileHandle.standardError.write(Data("cannot read \(args[1])\n".utf8)); exit(2)
}

let side = 1024
let inset = 0.80                                   // margin so the ear is not jammed to the edges
guard let ctx = CGContext(data: nil, width: side, height: side,
                          bitsPerComponent: 8, bytesPerRow: 0,
                          space: CGColorSpaceCreateDeviceRGB(),
                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    FileHandle.standardError.write(Data("cannot make context\n".utf8)); exit(3)
}

ctx.setFillColor(red: 1.0, green: 0.851, blue: 0.702, alpha: 1)      // #FFD9B3
ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))

let w = Double(image.width), h = Double(image.height)
let scale = min(Double(side) * inset / w, Double(side) * inset / h)
let dw = w * scale, dh = h * scale
ctx.interpolationQuality = .high
ctx.draw(image, in: CGRect(x: (Double(side) - dw) / 2, y: (Double(side) - dh) / 2,
                           width: dw, height: dh))

guard let out = ctx.makeImage() else { exit(4) }
let outURL = URL(fileURLWithPath: args[2])
guard let dest = CGImageDestinationCreateWithURL(outURL as CFURL, UTType.png.identifier as CFString, 1, nil) else { exit(5) }
CGImageDestinationAddImage(dest, out, nil)
guard CGImageDestinationFinalize(dest) else { exit(6) }
print("wrote \(args[2]) — \(side)x\(side), opaque")
