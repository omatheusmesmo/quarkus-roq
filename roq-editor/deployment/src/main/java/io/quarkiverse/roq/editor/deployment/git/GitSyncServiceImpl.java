package io.quarkiverse.roq.editor.deployment.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.jboss.logging.Logger;

import io.quarkiverse.roq.editor.runtime.devui.RoqEditorConfig;
import io.quarkiverse.roq.editor.runtime.devui.git.GitStatusInfo;
import io.quarkiverse.roq.editor.runtime.devui.git.GitSyncResult;
import io.quarkiverse.roq.frontmatter.runtime.config.RoqSiteConfig;

/**
 * Service providing Git operations for the Roq Editor Dev UI.
 */
public class GitSyncServiceImpl implements GitSyncService {

    private static final Logger LOG = Logger.getLogger(GitSyncServiceImpl.class);
    private static final Pattern SCP_LIKE_SSH_URL = Pattern.compile("^[^@]+@[^:]+:[^/].*$");

    private static final String ERR_AUTH_FAILED = "AUTH_FAILED:SSH authentication failed. Please check your passphrase.";
    private static final String ERR_AUTH_REQUIRED = "AUTH_REQUIRED:SSH passphrase required for remote operations.";

    private final RoqEditorConfig editorConfig;
    private final RoqSiteConfig siteConfig;
    private final File rootDirectory;

    public GitSyncServiceImpl(RoqEditorConfig editorConfig, RoqSiteConfig siteConfig, File rootDirectory) {
        this.editorConfig = editorConfig;
        this.siteConfig = siteConfig;
        this.rootDirectory = rootDirectory;
    }

    @Override
    public GitStatusInfo getStatus(String passphrase, boolean skipFetch) {
        try (Repository repository = openRepository()) {
            if (repository == null) {
                return new GitStatusInfo(false, false, false, "no-git-repo", 0, 0, Collections.emptyList(), false, false, "SAFE", Collections.emptyList(), false);
            }

            String currentBranch = repository.getBranch();
            String remoteUrl = repository.getConfig().getString("remote", "origin", "url");
            RepositoryState repoState = repository.getRepositoryState();
            boolean isSsh = isSshUrl(remoteUrl);

            try (Git git = new Git(repository)) {
                Status status = git.status().call();
                String rootPrefix = resolveWorkingPrefix(repository);
                List<String> contentChanges = extractSignificantContentChanges(status, rootPrefix);
                boolean hasUnpublished = !contentChanges.isEmpty();
                List<String> conflictFiles = new ArrayList<>(status.getConflicting());
                boolean hasConflicts = !conflictFiles.isEmpty() || repoState != RepositoryState.SAFE;

                // AUTH PRIORITY (SSH ONLY)
                boolean authMissing = isSsh && (passphrase == null || passphrase.isEmpty());
                if (authMissing) {
                    return new GitStatusInfo(false, hasUnpublished, false, currentBranch, 0, 0, contentChanges, true, hasConflicts, repoState.name(), conflictFiles, true);
                }

                if (!skipFetch) {
                    try {
                        performFetch(git, passphrase);
                    } catch (Exception fetchEx) {
                        if (isAuthenticationError(fetchEx) && isSsh) {
                            LOG.warn("SSH authentication failed during fetch poll: " + fetchEx.getMessage());
                            return new GitStatusInfo(false, hasUnpublished, false, currentBranch, 0, 0, contentChanges, true, hasConflicts, repoState.name(), conflictFiles, true);
                        }
                    }
                }

                BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, currentBranch);
                int aheadCount = (trackingStatus != null) ? trackingStatus.getAheadCount() : 0;
                int behindCount = (trackingStatus != null) ? trackingStatus.getBehindCount() : 0;

                boolean isUpToDate = aheadCount == 0 && behindCount == 0 && !hasUnpublished && repoState == RepositoryState.SAFE && !hasConflicts;

                return new GitStatusInfo(isUpToDate, hasUnpublished, behindCount > 0, currentBranch,
                        aheadCount, behindCount, contentChanges, false, hasConflicts, repoState.name(), conflictFiles, isSsh);
            }
        } catch (Exception e) {
            boolean isSsh = false;
            try (Repository repository = openRepository()) {
                if (repository != null) isSsh = isSshUrl(repository.getConfig().getString("remote", "origin", "url"));
            } catch (Exception ignored) {}

            boolean authFailed = isAuthenticationError(e) && isSsh;
            if (authFailed) LOG.warn("SSH authentication failed: " + e.getMessage());
            else if (!isAuthenticationError(e)) LOG.error("Failed to retrieve Git repository status", e);
            
            try (Repository repository = openRepository(); Git git = new Git(repository)) {
                Status status = git.status().call();
                String currentBranch = repository.getBranch();
                List<String> conflictFiles = new ArrayList<>(status.getConflicting());
                return new GitStatusInfo(false, false, false, currentBranch, 0, 0, Collections.emptyList(), authFailed, !conflictFiles.isEmpty(), repository.getRepositoryState().name(), conflictFiles, isSsh);
            } catch (Exception inner) {
                return new GitStatusInfo(false, false, false, "", 0, 0, Collections.emptyList(), authFailed, false, "ERROR", Collections.emptyList(), isSsh);
            }
        }
    }

    @Override
    public GitSyncResult sync(String passphrase) {
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            if (repository == null) return new GitSyncResult(false, "Git repository not found", false, Collections.emptyList(), false);

            String remoteUrl = repository.getConfig().getString("remote", "origin", "url");
            boolean isSsh = isSshUrl(remoteUrl);
            if (isSsh && (passphrase == null || passphrase.isEmpty())) {
                return new GitSyncResult(false, ERR_AUTH_REQUIRED, false, Collections.emptyList(), true);
            }

            boolean wasDirty = !git.status().call().isClean();
            if (wasDirty) {
                LOG.info("Stashing local changes before sync.");
                git.stashCreate().setIncludeUntracked(true).call();
            }

            GitSyncResult syncResult;
            try {
                PullResult pullResult = git.pull()
                        .setRemote("origin")
                        .setRemoteBranchName(repository.getBranch())
                        .setTransportConfigCallback(createTransportCallback(passphrase))
                        .call();

                if (!pullResult.isSuccessful()) {
                    syncResult = handleFailedPull(pullResult, git);
                } else {
                    syncResult = new GitSyncResult(true, "Synchronized successfully with remote", false, Collections.emptyList(), false);
                }
            } catch (Exception e) {
                boolean isAuth = isAuthenticationError(e) && isSsh;
                if (isAuth) {
                    LOG.warn("Sync authentication failed: " + e.getMessage());
                    syncResult = new GitSyncResult(false, ERR_AUTH_FAILED, false, Collections.emptyList(), true);
                } else {
                    LOG.error("Content synchronization failed", e);
                    syncResult = new GitSyncResult(false, "Sync failed: " + e.getMessage(), false, Collections.emptyList(), false);
                }
            }

            if (wasDirty) {
                try {
                    LOG.info("Restoring local changes after sync.");
                    git.stashApply().call();
                    git.stashDrop().setStashRef(0).call();
                } catch (StashApplyFailureException e) {
                    LOG.warn("Conflicts detected while restoring local changes.");
                    try {
                        Status status = git.status().call();
                        return new GitSyncResult(false, "Conflicts detected while restoring local changes. Resolve them manually.", true, new ArrayList<>(status.getConflicting()), false);
                    } catch (GitAPIException ex) {
                        return new GitSyncResult(false, "Conflicts detected in stash restore", true, Collections.emptyList(), false);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to restore stashed changes", e);
                }
            }
            return syncResult;
        } catch (Exception e) {
            boolean isSsh = false;
            try (Repository repository = openRepository()) {
                if (repository != null) isSsh = isSshUrl(repository.getConfig().getString("remote", "origin", "url"));
            } catch (Exception ignored) {}
            return new GitSyncResult(false, "Sync operation failed: " + e.getMessage(), false, Collections.emptyList(), isAuthenticationError(e) && isSsh);
        }
    }

    @Override
    public GitSyncResult push(String passphrase) {
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            if (repository == null) return new GitSyncResult(false, "Git repository not found", false, Collections.emptyList(), false);

            String remoteUrl = repository.getConfig().getString("remote", "origin", "url");
            boolean isSsh = isSshUrl(remoteUrl);
            if (isSsh && (passphrase == null || passphrase.isEmpty())) {
                return new GitSyncResult(false, ERR_AUTH_REQUIRED, false, Collections.emptyList(), true);
            }

            RepositoryState state = repository.getRepositoryState();
            // ALLOW PUSH if state is SAFE (Normal)
            if (state != RepositoryState.SAFE) {
                return new GitSyncResult(false, "Push blocked: Repository is in state " + state + ". Please finalize your merge/rebase first.", false, Collections.emptyList(), false);
            }

            git.push().setRemote("origin").setTransportConfigCallback(createTransportCallback(passphrase)).call();
            return new GitSyncResult(true, "Changes pushed successfully", false, Collections.emptyList(), false);
        } catch (Exception e) {
            boolean isSsh = false;
            try (Repository repository = openRepository()) {
                if (repository != null) isSsh = isSshUrl(repository.getConfig().getString("remote", "origin", "url"));
            } catch (Exception ignored) {}
            boolean isAuth = isAuthenticationError(e) && isSsh;
            if (isAuth) LOG.warn("Push authentication failed: " + e.getMessage());
            else LOG.error("Push operation failed", e);
            return new GitSyncResult(false, "Push error: " + e.getMessage(), false, Collections.emptyList(), isAuth);
        }
    }

    @Override
    public GitSyncResult publish(String commitMessage, String passphrase, List<String> filePaths) {
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            if (repository == null) return new GitSyncResult(false, "Git repository not found", false, Collections.emptyList(), false);

            String remoteUrl = repository.getConfig().getString("remote", "origin", "url");
            boolean isSsh = isSshUrl(remoteUrl);
            if (isSsh && (passphrase == null || passphrase.isEmpty())) {
                return new GitSyncResult(false, ERR_AUTH_REQUIRED, false, Collections.emptyList(), true);
            }

            // 1. Add files (including resolved conflicts)
            Status status = git.status().call();
            List<String> filesToAdd = new ArrayList<>(extractSignificantContentChanges(status, resolveWorkingPrefix(repository)));
            filesToAdd.addAll(status.getConflicting());

            if (!filesToAdd.isEmpty()) {
                for (String path : filesToAdd) git.add().addFilepattern(path).call();
            }

            // 2. Finalize Git State (Commit or Rebase)
            RepositoryState state = repository.getRepositoryState();
            if (state.isRebasing()) {
                try {
                    RebaseResult res = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call();
                    if (res.getStatus() == RebaseResult.Status.NOTHING_TO_COMMIT) {
                        res = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call();
                    }
                    if (res.getStatus() == RebaseResult.Status.STOPPED) {
                        return new GitSyncResult(false, "Rebase stopped: conflicts in next commit. Resolve and click Publish again.", true, new ArrayList<>(git.status().call().getConflicting()), false);
                    }
                    state = repository.getRepositoryState();
                } catch (Exception e) {
                    return new GitSyncResult(false, "Failed to continue rebase: " + e.getMessage(), false, Collections.emptyList(), false);
                }
            } else if (state == RepositoryState.MERGING || !filesToAdd.isEmpty()) {
                String msg = (commitMessage == null || commitMessage.isBlank()) ? editorConfig.sync().commitMessage().template() : commitMessage;
                git.commit().setMessage(msg).call();
                state = repository.getRepositoryState();
            }

            // 3. Auto-Merge if behind (SMART PUBLISH)
            BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, repository.getBranch());
            int behindCount = (trackingStatus != null) ? trackingStatus.getBehindCount() : 0;
            if (behindCount > 0 && state == RepositoryState.SAFE) {
                LOG.info("Performing auto-merge during publish due to remote changes.");
                GitSyncResult mergeResult = sync(passphrase);
                if (!mergeResult.success()) return mergeResult;
                state = repository.getRepositoryState();
            }

            // 4. Final Push
            if (state != RepositoryState.SAFE) {
                return new GitSyncResult(true, "Partial resolution successful (State: " + state + "). Continue resolving/publishing.", false, Collections.emptyList(), false);
            }

            return push(passphrase);
        } catch (Exception e) {
            boolean isSsh = false;
            try (Repository repository = openRepository()) {
                if (repository != null) isSsh = isSshUrl(repository.getConfig().getString("remote", "origin", "url"));
            } catch (Exception ignored) {}
            boolean isAuth = isAuthenticationError(e) && isSsh;
            if (isAuth) LOG.warn("Publish authentication failed: " + e.getMessage());
            else LOG.error("Publishing operation failed", e);
            return new GitSyncResult(false, "Publish error: " + e.getMessage(), false, Collections.emptyList(), isAuth);
        }
    }

    @Override public GitSyncResult publishAndSync(String commitMessage, String passphrase, List<String> filePaths) {
        GitSyncResult publishResult = publish(commitMessage, passphrase, filePaths);
        if (!publishResult.success()) return publishResult;
        return sync(passphrase);
    }

    protected File resolveWorkingDirectory() { return rootDirectory; }

    private Repository openRepository() throws IOException {
        File workingDir = resolveWorkingDirectory();
        FileRepositoryBuilder builder = new FileRepositoryBuilder().readEnvironment().findGitDir(workingDir);
        if (builder.getGitDir() == null) return null;
        return builder.build();
    }

    private void performFetch(Git git, String passphrase) throws Exception {
        git.fetch().setTransportConfigCallback(createTransportCallback(passphrase)).call();
    }

    private String resolveWorkingPrefix(Repository repository) {
        Path rootPath = repository.getWorkTree().toPath().toAbsolutePath().normalize();
        Path currentPath = rootDirectory.toPath().toAbsolutePath().normalize();
        if (currentPath.equals(rootPath)) return "";
        return rootPath.relativize(currentPath).toString().replace(File.separatorChar, '/') + "/";
    }

    private List<String> extractSignificantContentChanges(Status status, String prefix) {
        return Stream.of(status.getUncommittedChanges(), status.getUntracked(), status.getAdded(), status.getChanged(), status.getRemoved())
                .flatMap(Set::stream).filter(path -> isSignificantContentFile(path, prefix)).distinct().toList();
    }

    private boolean isSignificantContentFile(String path, String prefix) {
        if (path.startsWith(".git") || path.contains("/.git/")) return false;
        if (!prefix.isEmpty() && !path.startsWith(prefix)) return false;
        String relativePath = prefix.isEmpty() ? path : (path.startsWith(prefix) ? path.substring(prefix.length()) : path);
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        return relativePath.startsWith(siteConfig.contentDir()) || relativePath.startsWith(siteConfig.publicDir()) ||
                relativePath.startsWith(siteConfig.staticDir()) || relativePath.startsWith("posts/") ||
                relativePath.startsWith("data/") || relativePath.startsWith("templates/") || relativePath.equals("roq.java");
    }

    private GitSyncResult handleFailedPull(PullResult result, Git git) throws Exception {
        Status status = git.status().call();
        if (!status.getConflicting().isEmpty()) return new GitSyncResult(false, "Merge conflicts detected", true, new ArrayList<>(status.getConflicting()), false);
        if (result.getMergeResult() != null) {
            org.eclipse.jgit.api.MergeResult mergeResult = result.getMergeResult();
            if (mergeResult.getMergeStatus() == org.eclipse.jgit.api.MergeResult.MergeStatus.FAILED) {
                if (mergeResult.getFailingPaths() != null && !mergeResult.getFailingPaths().isEmpty()) {
                    List<String> failing = new ArrayList<>(mergeResult.getFailingPaths().keySet());
                    return new GitSyncResult(false, "Sync failed: local changes conflict.", false, failing, false);
                }
            }
            return new GitSyncResult(false, "Sync failed: " + mergeResult.getMergeStatus(), false, Collections.emptyList(), false);
        }
        if (!status.isClean()) {
            List<String> dirty = new ArrayList<>(status.getModified());
            dirty.addAll(status.getUntracked());
            return new GitSyncResult(false, "Sync failed: local edits conflict.", false, dirty, false);
        }
        return new GitSyncResult(false, "Sync failed", false, Collections.emptyList(), false);
    }

    private boolean isSshUrl(String url) {
        if (url == null) return false;
        return url.startsWith("ssh://") || url.startsWith("git@") || SCP_LIKE_SSH_URL.matcher(url).matches();
    }

    private boolean isAuthenticationError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.io.StreamCorruptedException || current instanceof javax.security.auth.login.FailedLoginException) return true;
            String msg = current.getMessage();
            if (msg != null) {
                String lowerMsg = msg.toLowerCase();
                if (lowerMsg.contains("auth") || lowerMsg.contains("passphrase") || lowerMsg.contains("no keys") ||
                    lowerMsg.contains("mismatched") || lowerMsg.contains("corrupted") || lowerMsg.contains("cannot log in") || lowerMsg.contains("publickey") || lowerMsg.contains("identity")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private TransportConfigCallback createTransportCallback(String passphrase) {
        return transport -> {
            if (transport instanceof SshTransport sshTransport) {
                SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                        .setPreferredAuthentications("publickey")
                        .setHomeDirectory(FS.DETECTED.userHome())
                        .setSshDirectory(new File(FS.DETECTED.userHome(), ".ssh"));
                if (passphrase != null && !passphrase.isEmpty()) {
                    builder.setKeyPasswordProvider(cp -> new KeyPasswordProvider() {
                        @Override public char[] getPassphrase(URIish uri, int attempt) { return passphrase.toCharArray(); }
                        @Override public boolean keyLoaded(URIish uri, int attempt, Exception err) { return false; }
                        @Override public void setAttempts(int attempts) {}
                    });
                }
                sshTransport.setSshSessionFactory(builder.build(null));
            }
        };
    }
}
