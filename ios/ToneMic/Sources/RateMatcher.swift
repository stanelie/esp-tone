import Foundation
import CoreAudio

/// Sample-rate conversion from the microphone's rate to the phone's, with the ratio under closed-
/// loop control so the buffer depth holds still.
///
/// WHY THIS EXISTS. The first version let AVAudioEngine resample: the source node was created at
/// 43860 Hz and the engine converted to the hardware 48000 Hz. That hides the one rate that
/// matters. The engine pulls the node at whatever its own converter decides, so the speed at which
/// the jitter buffer drained was neither chosen nor visible, and any mismatch with the arriving
/// stream accumulated as latency. MEASURED over 133 s: 43842 samples/s arriving (the microphone is
/// within 0.04% of the 43860 it claims), but only ~43260 consumed — the phone playing 1.33% slow.
/// The buffer grew 538 -> 1631 ms and the only thing fighting it was the trimmer, which is capped
/// at 0.5% and is audible when it works hard.
///
/// Running the node at the HARDWARE rate instead makes the pull rate exact — it is the audio
/// clock — and puts the conversion ratio here, where it can be measured and corrected. Correcting
/// it costs a pitch shift of a few hundredths of a percent, which no one can hear; the trimmer's
/// alternative is deleting a pitch period, which they can.
///
/// On interpolation quality: Catmull-Rom, as on Android (Resampler.kt). Not transparent — it rolls
/// off a little near Nyquist — but the microphone's own noise floor is ~35 dB above the
/// interpolation error at this ratio, and the resampling was happening anyway, just out of reach.
final class RateMatcher {
    /// Input frames per output frame at the nominal rates.
    private let nominalStep: Double
    /// The fractional correction learned by the loop below. Telemetry logs it, because it IS the
    /// measurement of the true rate mismatch between the microphone and this phone.
    private(set) var trim = 0.0
    private var step: Double

    /* Gains. The loop has to be slow: its output is pitch, and a correction that moves fast is a
     * warble. Proportional handles transients, integral learns the standing offset — without the
     * integral term a constant mismatch would need a permanent depth error to hold it. */
    /* Gains, and the division of labour between the two terms — which is the whole design.
     *
     * PROPORTIONAL does transient work: draining a startup overshoot, recovering after a stall.
     * INTEGRAL does one job only, learning the standing clock offset between the microphone and
     * this phone, which is MEASURED at -0.027%. So the integral is clamped to 0.1%: four times
     * the drift it has to learn, and nowhere near enough room to take over a transient.
     *
     * That clamp is the fix for the second windup failure. With the integral free to reach the
     * full 0.5% it absorbed the startup drain instead, and then could not shed it: at 5e-6 per ms
     * per second, unwinding 0.5% through errors of a few ms takes over a minute. MEASURED: the
     * depth error crossed zero at t=28 s with the trim still pinned at +0.500%, and the loop went
     * on draining until the buffer was 38 ms deep and about to glitch. A term with memory must
     * not be allowed to accumulate anything but the quantity it is there to estimate.
     *
     * The asymmetry is deliberate. Carrying extra latency costs nothing anyone can hear; letting
     * the buffer run dry costs a glitch. So the loop leans harder on the pedal when the depth is
     * BELOW target than when it is above. */
    private static let kp = 4.0e-5          // per ms of depth error
    private static let kpShallowBoost = 1.5 // extra urgency when the buffer is below target
    private static let ki = 5.0e-6          // per ms of depth error, per second
    /* The integral gets the FULL correction budget, not a fraction of it.
     *
     * This was 0.1%, on the reasoning that the integral should only ever hold the clock drift and
     * drift is 0.027%. That reasoning was wrong, and MEASURED on a LAN it cost an underrun: the
     * real standing imbalance was +0.360%, the integral was pinned at -0.033%, and the loop had
     * to make up the difference with the proportional term — which at kp = 4e-5 needs about
     * 90 ms of PERMANENT depth error to supply. The depth duly slid from 75 ms to 47 ms over
     * eleven seconds and ran dry, and the underrun then inflated the target by 150 ms for the
     * next minute.
     *
     * Capping the integral below the offset it exists to absorb guarantees a standing error, and
     * the error shows up as either needless latency or an underrun. A steady 0.5% is 8.6 cents of
     * pitch, which is inaudible — so there is nothing to protect against here. The protection
     * that matters is the anti-windup rule plus the band below, which keep the integral from
     * absorbing TRANSIENTS; its magnitude was never the risk. */
    private static let maxIntegral = 0.005
    private static let deadbandMs = 20.0    // don't chase ordinary burstiness
    /* Wide enough to converge, narrow enough not to chase a startup excursion.
     *
     * 15 ms was a deadlock: the integral could only move when the error was already small, and
     * the error could only become small once the integral had learned the offset. */
    private static let integralBandMs = 150.0

    private static let maxTrim = 0.005

    /// Output frames the engine has asked for. Divided by elapsed time this is the rate the
    /// engine actually pulls at — which must equal the hardware rate, and is the assumption the
    /// whole design rests on. It was inferred from ring consumption before; now it is measured.
    private(set) var outFrames = 0

    /* How long the render block takes, and what share of real time that is.
     *
     * The design assumes the engine pulls at the hardware clock. MEASURED on a Bluetooth route it
     * pulled 5.5% slow, which no rate correction can absorb, so the buffer grew to 3.6 s. Either
     * this block is overrunning its deadline — the DSP chain moved in here, and the code it
     * replaced was deliberately "nothing but a copy" — or the route itself is stalling. The duty
     * cycle tells the two apart: near 1.0 means we are the problem, near 0 means we are not. */
    private(set) var renderCalls = 0
    private(set) var renderNanos: UInt64 = 0
    private(set) var renderMaxNanos: UInt64 = 0

    /* The TRUE playout clock, read from the render timestamp instead of assumed.
     *
     * This whole design rests on "the engine pulls at the rate the node declares", and that
     * assumption has now failed twice: once because AVAudioEngine's own converter was in the path
     * (-1.33%) and once because the render block overran its deadline and the engine skipped
     * renders (-4.37%). Both were found only by inferring the rate from buffer growth, days apart.
     * CoreAudio hands the answer to every callback, so take it.
     *
     * mSampleTime advances by frameCount per call when all is well; a jump beyond that is the
     * engine skipping renders, which is starvation and is counted as such. Deliberately NOT fed
     * back into nominalStep: if the engine renders 4% fewer frames than it should, matching the
     * step to that would squeeze a second of arriving audio into 0.96 s of output — trading a
     * latency fault for a 4% pitch error, which is far more audible. The surplus is physical and
     * belongs to the buffer's ceiling; this is here to say so, not to hide it.
     */
    private(set) var measuredOutRate = 0.0
    private(set) var skippedFrames = 0
    private var firstSampleTime = -1.0
    private var firstHostTime: UInt64 = 0
    private var nextSampleTime = -1.0
    private static let hostToSeconds: Double = {
        var tb = mach_timebase_info_data_t()
        mach_timebase_info(&tb)
        return Double(tb.numer) / Double(tb.denom) / 1e9
    }()

    /// Called from the render block with CoreAudio's own timestamp.
    func noteTimestamp(_ ts: AudioTimeStamp, frames: Int) {
        let flags = ts.mFlags
        guard flags.contains(.sampleTimeValid), flags.contains(.hostTimeValid) else { return }
        if firstSampleTime < 0 {
            firstSampleTime = ts.mSampleTime
            firstHostTime = ts.mHostTime
        } else {
            if ts.mSampleTime > nextSampleTime + 0.5 {
                skippedFrames += Int(ts.mSampleTime - nextSampleTime)
            }
            let dSamples = ts.mSampleTime - firstSampleTime
            let dSeconds = Double(ts.mHostTime - firstHostTime) * Self.hostToSeconds
            if dSeconds > 1 { measuredOutRate = dSamples / dSeconds }
        }
        nextSampleTime = ts.mSampleTime + Double(frames)
    }

    private var phase = 0.0
    private var p0: Float = 0, p1: Float = 0, p2: Float = 0, p3: Float = 0
    private var primed = false
    private var scratch: [Float]

    let inRate: Int
    let outRate: Double

    init(inRate: Int, outRate: Double, maxOutFrames: Int = 8192) {
        self.inRate = inRate
        self.outRate = outRate
        self.nominalStep = Double(inRate) / outRate
        self.step = nominalStep
        // Pre-sized so the realtime callback never allocates. inNeeded <= n*step + 1 <= n + 1.
        self.scratch = [Float](repeating: 0, count: maxOutFrames + 8)
    }

    /// Input frames this call will consume to produce `n` output frames.
    ///
    /// Exact, not an estimate, so the jitter buffer can be drained in a single locked call. Output
    /// k sits at absolute position `phase + k*step` measured in input frames, and one input frame
    /// is consumed each time that position crosses an integer — which stops after the last output.
    private func inputNeeded(for n: Int) -> Int {
        guard n > 0 else { return 0 }
        return Int((phase + Double(n - 1) * step).rounded(.down))
    }

    /// Renders `n` frames at the output rate, drawing what it needs from `buffer`, and updates the
    /// rate correction from the resulting buffer depth.
    func render(into out: UnsafeMutablePointer<Float>, frames n: Int, from buffer: JitterBuffer) {
        guard n > 0 else { return }
        outFrames += n
        let t0 = DispatchTime.now().uptimeNanoseconds
        defer {
            let dt = DispatchTime.now().uptimeNanoseconds - t0
            renderCalls += 1
            renderNanos += dt
            if dt > renderMaxNanos { renderMaxNanos = dt }
        }

        if !primed {
            // Three frames to fill the interpolation history; p0 stays 0 for one sample.
            scratch.withUnsafeMutableBufferPointer { buffer.pull(into: $0.baseAddress!, frames: 3) }
            p1 = scratch[0]; p2 = scratch[1]; p3 = scratch[2]
            primed = true
        }

        let need = min(inputNeeded(for: n), scratch.count)
        if need > 0 {
            scratch.withUnsafeMutableBufferPointer {
                buffer.pull(into: $0.baseAddress!, frames: need)
            }
        }

        var o = 0, i = 0
        while o < n {
            while phase < 1.0 && o < n {
                let mu = Float(phase)
                // Catmull-Rom: passes through p1 and p2, C1 continuous across calls.
                let a = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3
                let b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3
                let d = -0.5 * p0 + 0.5 * p2
                out[o] = ((a * mu + b) * mu + d) * mu + p1
                o += 1
                phase += step
            }
            if o >= n { break }
            p0 = p1; p1 = p2; p2 = p3
            p3 = i < need ? scratch[i] : p3
            i += 1
            phase -= 1.0
        }

        let signal = buffer.loopInput()
        update(dt: Double(n) / outRate, depth: signal.errorMs, feedforward: signal.giveBack)
    }

    /// Nudges the ratio toward holding the buffer at its target.
    ///
    /// `error` is positive when the buffer is deeper than the target, which calls for consuming
    /// input faster — a larger step.
    ///
    /// Both guards below exist because the first version had neither and wound up to the clamp.
    /// The integral only moves on error worth correcting, and it does not move at all when the
    /// correction is already against the stop in that direction — classic anti-windup. Without
    /// it, any error the loop cannot null (and with a target that drifts downward there is always
    /// one) walks the integrator to its limit and leaves it there.
    private func update(dt: Double, depth error: Double, feedforward ff: Double) {
        let mag = abs(error)

        /* The integral runs only NEAR equilibrium, which is the reverse of the first version.
         *
         * Both terms were gated on |error| > deadband, so the integral accumulated exactly when
         * the error was large — during a transient, the one thing it must not absorb. MEASURED on
         * the Android client, which shares this design: a target step from 76 to 84 ms drove the
         * error to -27 ms and wound the integral straight onto its 0.1% clamp, where it had
         * stopped estimating anything. Large errors belong to the proportional term; the integral
         * is here to estimate a slowly-varying clock offset and nothing else. */
        if mag < Self.integralBandMs {
            trim = min(Self.maxIntegral, max(-Self.maxIntegral, trim + Self.ki * error * dt))
        }

        let p: Double
        if mag > Self.deadbandMs {
            p = (error < 0 ? Self.kp * Self.kpShallowBoost : Self.kp) * error
        } else {
            p = 0
        }
        step = nominalStep * (1 + min(Self.maxTrim, max(-Self.maxTrim, p + trim + ff)))
    }
}
