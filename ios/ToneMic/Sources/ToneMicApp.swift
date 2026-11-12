import SwiftUI

@main
struct ToneMicApp: App {
    init() {
        // print() goes to stdout, which is block-buffered when it is not a terminal - so under
        // `devicectl ... --console` the diagnostics sit in the buffer and never appear. Unbuffer it.
        setvbuf(stdout, nil, _IONBF, 0)
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
