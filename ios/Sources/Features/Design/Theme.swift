import SwiftUI

enum OrynodeTheme {
    /// Cool white with a faint blue cast — matches the logo's white field.
    static let paper = Color(red: 0.965, green: 0.973, blue: 0.992)
    static let paperDeep = Color(red: 0.910, green: 0.925, blue: 0.965)

    /// Deep slate for readable type on light surfaces.
    static let ink = Color(red: 0.071, green: 0.094, blue: 0.165)
    static let inkSoft = ink.opacity(0.62)
    static let inkFaint = ink.opacity(0.38)

    /// Mid logo blue (`#3B7BEA`).
    static let accent = Color(red: 0.231, green: 0.482, blue: 0.918)
    static let accentSoft = accent.opacity(0.12)

    /// Logo cyan → violet stops.
    static let brandCyan = Color(red: 0.169, green: 0.706, blue: 0.941)   // #2BB4F0
    static let brandBlue = Color(red: 0.231, green: 0.482, blue: 0.918)   // #3B7BEA
    static let brandIndigo = Color(red: 0.357, green: 0.373, blue: 0.910) // #5B5FE8
    static let brandViolet = Color(red: 0.478, green: 0.247, blue: 0.831) // #7A3FD4

    static let rule = ink.opacity(0.10)
    static let caution = Color(red: 0.690, green: 0.325, blue: 0.220)
    static let cautionFill = caution.opacity(0.08)

    static var brandGradient: LinearGradient {
        LinearGradient(
            colors: [brandCyan, brandBlue, brandIndigo, brandViolet],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    static var brandGradientHorizontal: LinearGradient {
        LinearGradient(
            colors: [brandCyan, brandBlue, brandViolet],
            startPoint: .leading,
            endPoint: .trailing
        )
    }
}

struct PaperBackground: View {
    var body: some View {
        ZStack {
            OrynodeTheme.paper
            LinearGradient(
                colors: [
                    OrynodeTheme.brandCyan.opacity(0.10),
                    .clear,
                    OrynodeTheme.brandViolet.opacity(0.08),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            RadialGradient(
                colors: [
                    OrynodeTheme.brandBlue.opacity(0.10),
                    .clear,
                ],
                center: .topTrailing,
                startRadius: 12,
                endRadius: 340
            )
            RadialGradient(
                colors: [
                    OrynodeTheme.brandViolet.opacity(0.07),
                    .clear,
                ],
                center: .bottomLeading,
                startRadius: 8,
                endRadius: 280
            )
        }
        .ignoresSafeArea()
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var isEnabled = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(Color.white.opacity(isEnabled ? 1 : 0.55))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background {
                if isEnabled {
                    OrynodeTheme.brandGradient
                        .opacity(configuration.isPressed ? 0.88 : 1)
                } else {
                    OrynodeTheme.brandBlue.opacity(0.40)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .medium))
            .foregroundStyle(OrynodeTheme.ink)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(OrynodeTheme.paperDeep.opacity(configuration.isPressed ? 0.9 : 1))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(OrynodeTheme.rule, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

extension ToolbarContent {
    /// iOS 26+ Liquid Glass wraps toolbar items in a shared capsule; hide it for brand/chrome-free items.
    @ToolbarContentBuilder
    func hideSharedToolbarBackgroundIfAvailable() -> some ToolbarContent {
        if #available(iOS 26.0, *) {
            sharedBackgroundVisibility(.hidden)
        } else {
            self
        }
    }
}

/// Shared identity: logo + name + 本地AI知识库 (same on cover and welcome).
struct OnboardingBrandHeader: View {
    var body: some View {
        VStack(spacing: 12) {
            Image("BrandLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 96, height: 96)
                .accessibilityHidden(true)

            Text("Orynode Mobile AI")
                .font(.system(size: 24, weight: .semibold, design: .rounded))
                .foregroundStyle(OrynodeTheme.ink)
                .multilineTextAlignment(.center)

            Text("本地AI知识库")
                .font(.system(size: 16, weight: .semibold, design: .rounded))
                .foregroundStyle(OrynodeTheme.accent)
                .tracking(2)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Orynode Mobile AI 本地AI知识库")
    }
}

/// Fixed-height slot under the brand mark so progress does not shift the header.
struct OnboardingStatusSlot: View {
    var message: String?
    var isActive: Bool

    static let height: CGFloat = 64

    var body: some View {
        VStack(spacing: 12) {
            if isActive {
                ProgressView()
                    .tint(OrynodeTheme.accent)
                Text(message ?? "请稍候…")
                    .font(.system(size: 14))
                    .foregroundStyle(OrynodeTheme.inkSoft)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(maxWidth: .infinity, minHeight: Self.height, maxHeight: Self.height, alignment: .top)
        .padding(.top, 18)
        .accessibilityHidden(!isActive)
    }
}

/// Cover and welcome share one vertical composition.
/// Brand stays optically centered; footer height is always reserved so the mark does not jump.
struct OnboardingStageLayout<Footer: View>: View {
    var statusMessage: String?
    var showsProgress: Bool
    @ViewBuilder var footer: () -> Footer

    /// Reserved so Launch and Model Setup keep the same brand Y.
    private var footerReserve: CGFloat { 120 }

    var body: some View {
        ZStack {
            PaperBackground()
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                OnboardingBrandHeader()
                OnboardingStatusSlot(
                    message: statusMessage,
                    isActive: showsProgress
                )
                Spacer(minLength: 0)
                footer()
                    .frame(
                        maxWidth: .infinity,
                        minHeight: footerReserve,
                        maxHeight: footerReserve,
                        alignment: .bottom
                    )
                    .padding(.bottom, 28)
            }
            .padding(.horizontal, 32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
