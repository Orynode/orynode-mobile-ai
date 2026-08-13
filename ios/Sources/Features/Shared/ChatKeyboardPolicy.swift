import SwiftUI
import UIKit

/// Shared keyboard contract for chat composers — no keyboard accessory “Done”.
///
/// Rules:
/// 1. One focus owner — the composer via `@FocusState` (tap field to show).
/// 2. Dismiss like chat apps: drag-scroll the list, tap the message area, send, or open a sheet.
/// 3. Never add `ToolbarItemGroup(placement: .keyboard)` Done buttons.
struct ChatKeyboardPolicy: ViewModifier {
    enum ScrollDismiss {
        case interactive
        case immediate

        var mode: ScrollDismissesKeyboardMode {
            switch self {
            case .interactive: .interactively
            case .immediate: .immediately
            }
        }
    }

    var scrollDismiss: ScrollDismiss = .interactive
    var focused: FocusState<Bool>.Binding?

    func body(content: Content) -> some View {
        content
            .scrollDismissesKeyboard(scrollDismiss.mode)
            // Tap message area to resign — simultaneous so citation / bubble buttons still receive hits.
            .simultaneousGesture(
                TapGesture().onEnded {
                    focused?.wrappedValue = false
                    ChatKeyboard.dismiss()
                }
            )
    }
}

enum ChatKeyboard {
    static func dismiss() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }
}

extension View {
    /// Apply the shared chat keyboard contract to a scrollable message container.
    func chatKeyboardPolicy(
        _ scrollDismiss: ChatKeyboardPolicy.ScrollDismiss = .interactive,
        focused: FocusState<Bool>.Binding? = nil
    ) -> some View {
        modifier(ChatKeyboardPolicy(scrollDismiss: scrollDismiss, focused: focused))
    }

    /// Dismiss keyboard when `trigger` becomes `true` (send, navigate, open sheet, answering…).
    func dismissKeyboard(when trigger: Bool) -> some View {
        onChange(of: trigger) { _, active in
            if active { ChatKeyboard.dismiss() }
        }
    }
}
