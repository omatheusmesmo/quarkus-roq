package io.quarkiverse.roq.editor.runtime.devui.git;

import java.util.List;

/**
 * Information about the Git repository status.
 * Updated for Smart Sync support.
 */
public record GitStatusInfo(
        boolean upToDate,
        boolean hasUnpublished,
        boolean hasRemoteChanges,
        String branch,
        int ahead,
        int behind,
        List<String> pendingFiles,
        boolean authFailed,
        boolean hasConflicts,
        String repositoryState,
        List<String> conflictFiles,
        boolean isSsh) {

    public GitStatusInfo(boolean upToDate, boolean hasUnpublished, boolean hasRemoteChanges, String branch,
            int ahead, int behind, List<String> pendingFiles, boolean authFailed, boolean hasConflicts,
            String repositoryState, List<String> conflictFiles) {
        this(upToDate, hasUnpublished, hasRemoteChanges, branch, ahead, behind, pendingFiles, authFailed, hasConflicts,
                repositoryState, conflictFiles, false);
    }
}
