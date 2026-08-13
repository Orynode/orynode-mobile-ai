import SwiftUI

struct OpenSourceNoticesView: View {
    private var noticeText: String {
        if let url = Bundle.main.url(forResource: "NOTICE", withExtension: nil),
           let text = try? String(contentsOf: url, encoding: .utf8),
           !text.isEmpty {
            return text
        }
        return """
        未能加载随附 NOTICE。请参阅仓库中的 ios/NOTICE 与根目录 LICENSE（MIT）。
        """
    }

    var body: some View {
        ZStack {
            PaperBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("本应用源码")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(OrynodeTheme.inkSoft)
                        Link(destination: OrynodeOpenSource.repositoryURL) {
                            HStack(spacing: 8) {
                                Image("GitHubMark")
                                    .resizable()
                                    .renderingMode(.template)
                                    .scaledToFit()
                                    .frame(width: 18, height: 18)
                                    .foregroundStyle(OrynodeTheme.accent)
                                Text(OrynodeOpenSource.repositoryName)
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(OrynodeTheme.accent)
                            }
                        }
                        .accessibilityLabel("打开源码仓库 \(OrynodeOpenSource.repositoryName)")
                        Text("许可证：\(OrynodeOpenSource.licenseName)")
                            .font(.system(size: 13))
                            .foregroundStyle(OrynodeTheme.inkSoft)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(OrynodeTheme.paperDeep.opacity(0.55))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                    Text(noticeText)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(OrynodeTheme.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .padding(20)
            }
        }
        .navigationTitle("开源许可")
        .navigationBarTitleDisplayMode(.inline)
    }
}
