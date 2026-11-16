import Foundation

enum CaptureMode: String, CaseIterable, Identifiable {
    case fullHD240, fullHD120, fullHD60, ultraHD60
    var id: String { rawValue }
    var label: String {
        switch self {
        case .fullHD240: return "1080p · 240 fps"
        case .fullHD120: return "1080p · 120 fps"
        case .fullHD60: return "1080p · 60 fps"
        case .ultraHD60: return "4K · 60 fps"
        }
    }
    var width: Int32 { self == .ultraHD60 ? 3840 : 1920 }
    var height: Int32 { self == .ultraHD60 ? 2160 : 1080 }
    var fps: Int32 {
        switch self { case .fullHD240: return 240; case .fullHD120: return 120; default: return 60 }
    }
    var bitRate: Int {
        switch self {
        case .fullHD240: return 50_000_000
        case .fullHD120: return 30_000_000
        case .fullHD60: return 20_000_000
        case .ultraHD60: return 60_000_000
        }
    }
}

// O(1) memory. Rates use measurement times, never callback arrival times.
struct SampleStats {
    var count = 0
    var first: Double?
    var last: Double?
    var maxGap = 0.0
    var nonmonotonic = 0
    var gapsRelativeToRequest = 0
    var rejected = 0
    @discardableResult
    mutating func add(_ time: Double, expectedHz: Double) -> Bool {
        guard time.isFinite else { rejected += 1; return false }
        if let previous = last, time <= previous {
            nonmonotonic += 1; rejected += 1; return false
        }
        if first == nil { first = time }
        if let previous = last {
            let delta = time - previous
            if delta <= 0 { nonmonotonic += 1 }
            if delta > 1.5 / expectedHz { gapsRelativeToRequest += 1 }
            maxGap = max(maxGap, delta)
        }
        last = time; count += 1
        return true
    }
    var hz: Double {
        guard count > 1, let first = first, let last = last, last > first else { return 0 }
        return Double(count - 1) / (last - first)
    }
    var json: [String: Any] {
        ["count": count, "average_hz": hz, "max_gap_s": maxGap,
         "nonmonotonic": nonmonotonic, "rejected": rejected,
         "gaps_relative_to_requested_rate": gapsRelativeToRequest]
    }
}
