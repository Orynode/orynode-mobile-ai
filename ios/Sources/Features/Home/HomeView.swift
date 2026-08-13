import SwiftUI

struct HomeView: View {
    @ObservedObject var appModel: AppModel
    @ObservedObject private var knowledgeModel: KnowledgeBaseModel
    @State private var showsHistory = false
    @State private var showsChat = false

    init(appModel: AppModel) {
        self.appModel = appModel
        knowledgeModel = appModel.knowledgeBaseModel
    }

    var body: some View {
        NavigationStack {
            ZStack {
                PaperBackground()
                ScrollView(showsIndicators: false) {
                    KnowledgeBaseView(model: knowledgeModel, showsChat: $showsChat)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 40)
                }

                if showsHistory {
                    KnowledgeChatHistoryDrawer(
                        sessions: knowledgeModel.sessions,
                        activeSessionID: knowledgeModel.activeSessionID,
                        onClose: { closeHistory() },
                        onNewChat: {
                            knowledgeModel.startNewChat()
                            closeHistory()
                            showsChat = true
                        },
                        onSelect: { id in
                            knowledgeModel.openSession(id)
                            closeHistory()
                            showsChat = true
                        },
                        onDelete: { id in
                            knowledgeModel.deleteSession(id)
                        }
                    )
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                    .zIndex(2)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 7) {
                        Image("BrandLogo")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 24, height: 24)
                            .accessibilityHidden(true)
                        Text("Orynode")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(OrynodeTheme.ink)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(1)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Orynode")
                }
                .hideSharedToolbarBackgroundIfAvailable()

                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        knowledgeModel.loadChatHistory()
                        withAnimation(.easeOut(duration: 0.22)) {
                            showsHistory = true
                        }
                    } label: {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(OrynodeTheme.ink)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("历史对话")

                    Button {
                        appModel.showsSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(OrynodeTheme.ink)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("设置")
                }
                .hideSharedToolbarBackgroundIfAvailable()
            }
            .toolbarBackground(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showsChat) {
                KnowledgeChatView(model: knowledgeModel)
            }
            .task {
                knowledgeModel.loadChatHistory()
            }
        }
        .sheet(isPresented: $appModel.showsSettings) {
            SettingsView(appModel: appModel)
        }
        .alert(
            "知识库暂时不可用",
            isPresented: Binding(
                get: { knowledgeModel.errorMessage != nil },
                set: { if !$0 { knowledgeModel.clearError() } }
            )
        ) {
            Button("好") { knowledgeModel.clearError() }
        } message: {
            Text(knowledgeModel.errorMessage ?? "")
        }
    }

    private func closeHistory() {
        withAnimation(.easeOut(duration: 0.2)) {
            showsHistory = false
        }
    }
}
