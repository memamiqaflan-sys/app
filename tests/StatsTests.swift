import Foundation

@main
struct StatsTests {
    static func main() {
        var stats = SampleStats()
        precondition(stats.add(100, expectedHz: 1000))
        precondition(stats.add(100.01, expectedHz: 1000))
        precondition(abs(stats.hz - 100) < 0.0001)
        precondition(!stats.add(100.01, expectedHz: 1000))
        precondition(!stats.add(99, expectedHz: 1000))
        precondition(!stats.add(.nan, expectedHz: 1000))
        precondition(!stats.add(.infinity, expectedHz: 1000))
        precondition(stats.count == 2 && stats.rejected == 4)
        precondition(stats.add(100.03, expectedHz: 1000))
        precondition(stats.maxGap > 0.019)
        precondition(stats.nonmonotonic == 2)
        print("Timestamp statistics: rejection, rate, and gap checks passed")
    }
}
