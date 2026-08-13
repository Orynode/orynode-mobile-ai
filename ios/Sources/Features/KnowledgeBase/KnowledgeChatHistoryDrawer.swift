import SwiftUI

/// Trailing side drawer listing on-device chat sessions.
struct KnowledgeChatHistoryDrawer: View {
    let sessions: [KnowledgeChatSession]
    let activeSessionID: UUID?
    var onClose: () -> Void
    var onNewChat: () -> Void
    var onSelect: (UUID) -> Void
    var onDelete: (UUID) -> Void

    private let panelWidthRatio: CGFloat = 0.82

    var body: some View {
        GeometryReader { geo in
            let width = min(340, geo.size.width * panelWidthRatio)
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.28)
                    .ignoresSafeArea()
                    .onTapGesture(perform: onClose)

                VStack(alignment: .leading, spacing: 0) {
                    header
                    Divider().overlay(OrynodeTheme.rule)
                    if sessions.isEmpty {
                        emptyState
                    } else {
                        sessionList
                    }
                }
                .frame(width: width)
                .frame(maxHeight: .infinity, alignment: .top)
                .background(OrynodeTheme.paper.opacity(0.98))
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(OrynodeTheme.rule)
                        .frame(width: 1)
                }
                .ignoresSafeArea(edges: .bottom)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Text("历史对话")
                .font(.system(size: 18, weight: .semibold, design: .rounded))
                .foregroundStyle(OrynodeTheme.ink)
            Spacer()
            Button(action: onNewChat) {
                Label("新对话", systemImage: "square.and.pencil")
                    .labelStyle(.iconOnly)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(OrynodeTheme.accent)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel("新建对话")
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(OrynodeTheme.inkSoft)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel("关闭")
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("还没有历史记录")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(OrynodeTheme.ink)
            Text("提问后会自动保存在本机。")
                .font(.system(size: 14))
                .foregroundStyle(OrynodeTheme.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }

    private var sessionList: some View {
        List {
            ForEach(sessions) { session in
                Button {
                    onSelect(session.id)
                } label: {
                    sessionRow(session)
                }
                .buttonStyle(.plain)
                .listRowBackground(
                    session.id == activeSessionID
                        ? OrynodeTheme.accentSoft
                        : Color.white.opacity(0.55)
                )
                .listRowInsets(EdgeInsets(top: 10, leading: 14, bottom: 10, trailing: 14))
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        onDelete(session.id)
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.clear)
    }

    private func sessionRow(_ session: KnowledgeChatSession) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(session.title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(OrynodeTheme.ink)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            HStack(spacing: 8) {
                Text(relativeDate(session.updatedAt))
                    .font(.system(size: 12))
                    .foregroundStyle(OrynodeTheme.inkFaint)
                Text("·")
                    .foregroundStyle(OrynodeTheme.inkFaint)
                Text("\(session.messages.count) 条")
                    .font(.system(size: 12))
                    .foregroundStyle(OrynodeTheme.inkFaint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func relativeDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
