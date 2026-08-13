import SwiftUI

struct LaunchView: View {
    @ObservedObject var appModel: AppModel

    private var isLoadingInstalledModel: Bool {
        if case .loading = appModel.runtimeState { return true }
        return false
    }

    var body: some View {
        OnboardingStageLayout(
            statusMessage: isLoadingInstalledModel ? appModel.statusMessage : nil,
            showsProgress: isLoadingInstalledModel
        ) {
            Color.clear
        }
    }
}
