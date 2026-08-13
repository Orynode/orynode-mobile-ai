import Foundation

/// DEBUG-only indexing fault points for capacity/recovery drills.
/// Launch args: `-KBFailAtFraction 0.1` (also `0.5` / `0.9`).
/// Cleared automatically after the first trip so a subsequent retry can finish.
public enum IndexFaultInjection: Sendable {
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var failAfterFraction: Double?
        var tripped = false
    }

    private static let state = State()

    public static func installFromProcessArguments(_ arguments: [String] = ProcessInfo.processInfo.arguments) {
        #if DEBUG
        guard let index = arguments.firstIndex(of: "-KBFailAtFraction"),
              arguments.indices.contains(index + 1),
              let value = Double(arguments[index + 1]),
              value > 0, value < 1 else {
            return
        }
        state.lock.lock()
        state.failAfterFraction = value
        state.tripped = false
        state.lock.unlock()
        #endif
    }

    public static func reset() {
        state.lock.lock()
        state.failAfterFraction = nil
        state.tripped = false
        state.lock.unlock()
    }

    /// Returns true once when committed/total crosses the configured fraction.
    public static func shouldFail(committed: Int, total: Int) -> Bool {
        #if DEBUG
        state.lock.lock()
        defer { state.lock.unlock() }
        guard let fraction = state.failAfterFraction, !state.tripped, total > 0, committed > 0 else {
            return false
        }
        let threshold = max(1, min(total - 1, Int((Double(total) * fraction).rounded(.down))))
        guard committed >= threshold else { return false }
        state.tripped = true
        return true
        #else
        return false
        #endif
    }
}
