import SwiftUI
import UIKit
import OrynodeInfrastructure

@main
struct OrynodeMobileAIApp: App {
    @StateObject private var appModel = AppModel(
        engine: LiteRTLMModelEngine(),
        modelStore: FileModelStore()
    )

    var body: some Scene {
        WindowGroup {
            RootView(appModel: appModel)
                .task {
                    await appModel.start()
                }
                .onReceive(
                    NotificationCenter.default.publisher(
                        for: UIApplication.didReceiveMemoryWarningNotification
                    )
                ) { _ in
                    appModel.handleMemoryWarning()
                }
        }
    }
}

private struct RootView: View {
    @ObservedObject var appModel: AppModel

    var body: some View {
        Group {
            switch appModel.phase {
            case .launching:
                LaunchView(appModel: appModel)
            case .needsModel:
                ModelSetupView(appModel: appModel)
            case .ready:
                HomeView(appModel: appModel)
            }
        }
        .preferredColorScheme(.light)
    }
}
