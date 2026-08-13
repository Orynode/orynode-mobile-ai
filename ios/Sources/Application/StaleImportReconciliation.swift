import Foundation
import OrynodeDomain

/// Marks force-quit / jetsam leftovers: `importing` with no live Task must not look like active work.
public enum StaleImportReconciliation {
    public static let interruptedMessage = "索引中断，请重试"

    public static func staleImportingIDs(
        in documents: [KnowledgeDocument],
        activeImportIDs: Set<UUID>
    ) -> [UUID] {
        documents.compactMap { document in
            guard document.state == .importing, !activeImportIDs.contains(document.id) else {
                return nil
            }
            return document.id
        }
    }
}
