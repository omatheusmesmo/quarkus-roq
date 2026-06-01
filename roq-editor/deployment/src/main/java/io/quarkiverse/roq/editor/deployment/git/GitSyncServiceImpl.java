package io.quarkiverse.roq.editor.deployment.git;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.jboss.logging.Logger;

import io.quarkiverse.roq.editor.runtime.devui.RoqEditorConfig;
import io.quarkiverse.roq.editor.runtime.devui.RoqEditorConfig.SyncConfig.Mode;
import io.quarkiverse.roq.editor.runtime.devui.RoqEditorConfig.SyncConfig.PrFlowConfig.CommitStrategy;
import io.quarkiverse.roq.editor.runtime.devui.git.GitStatusInfo;
import io.quarkiverse.roq.editor.runtime.devui.git.GitSyncResult;
import io.quarkiverse.roq.frontmatter.runtime.config.RoqSiteConfig;

/**
 * Service providing Git operations for the Roq Editor Dev UI.
 */
public class GitSyncServiceImpl implements GitSyncService {

    private static final Logger LOG = Logger.getLogger(GitSyncServiceImpl.class);

    private static final String MSG_STASH_BEFORE_SYNC = "Stashing local changes before sync.";
    private static final String MSG_RESTORE_STASH_AFTER_SYNC = "Restoring local changes after sync.";
    private static final String MSG_AUTO_MERGE_DURING_PUBLISH = "Performing auto-merge during publish due to remote changes.";
    private static final String MSG_SYNC_SUCCESS = "Synchronized successfully with remote";
    private static final String MSG_PUSH_SUCCESS = "Changes pushed successfully";
    private static final String MSG_REPO_NOT_FOUND = "Git repository not found";
    private static final String MSG_NO_GIT_REPO = "no-git-repo";
    private static final String MSG_NOTHING_TO_PUBLISH = "Nothing to publish";

    private static final DateTimeFormatter BRANCH_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final Pattern PR_URL_PATTERN = Pattern.compile(
            "https?://\\S+?(?:pull/new/|pull-requests/new|merge_requests/new)\\S*");

    private final File rootDirectory;
    private final RoqEditorConfig editorConfig;
    private final GitContentFilter contentFilter;
    private final GitOperationHelper operationHelper;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Boolean cachedIsSsh;
    private volatile boolean lastAuthFailed;
    private final GitCredentialHelper credentialHelper = new GitCredentialHelper();
    private final String configuredPassphrase;

    /**
     * Creates a new instance of GitSyncServiceImpl.
     *
     * @param editorConfig the editor configuration
     * @param siteConfig the site configuration
     * @param rootDirectory the root directory of the project
     */
    public GitSyncServiceImpl(RoqEditorConfig editorConfig, RoqSiteConfig siteConfig, File rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.editorConfig = editorConfig;
        this.contentFilter = new GitContentFilter(siteConfig, rootDirectory);
        this.operationHelper = new GitOperationHelper(editorConfig, contentFilter);
        this.configuredPassphrase = editorConfig.sync().sshPassphrase().filter(p -> !p.isEmpty()).orElse(null);
    }

    /**
     * Retrieves the current status of the Git repository including local changes,
     * remote tracking status (ahead/behind), and repository state.
     *
     * @param skipFetch if true, skip the network fetch operation
     * @return an object containing detailed Git status information
     */
    @Override
    public GitStatusInfo getStatus(boolean skipFetch) {
        lock.lock();
        try (Repository repository = openRepository()) {
            if (repository == null) {
                return new GitStatusInfo(false, false, false, MSG_NO_GIT_REPO, 0, 0, Collections.emptyList(), false, false,
                        "SAFE", Collections.emptyList(), false);
            }

            String currentBranch = repository.getBranch();
            RepositoryState repoState = repository.getRepositoryState();
            boolean isSsh = GitTransportHelper.isSsh(repository);
            cachedIsSsh = isSsh;

            try (Git git = new Git(repository)) {
                Status status = git.status().call();
                String rootPrefix = contentFilter.resolveWorkingPrefix(repository);
                List<String> contentChanges = contentFilter.extractSignificantContentChanges(status, rootPrefix);
                boolean hasUnpublished = !contentChanges.isEmpty();
                List<String> conflictFiles = new ArrayList<>(status.getConflicting());
                boolean hasConflicts = !conflictFiles.isEmpty() || repoState != RepositoryState.SAFE;

                boolean needsAuth;
                if (!skipFetch) {
                    needsAuth = tryFetch(git, isSsh);
                    lastAuthFailed = needsAuth;
                } else {
                    needsAuth = lastAuthFailed;
                }

                int[] counts = getAheadBehindCounts(repository, currentBranch);
                int aheadCount = counts[0];
                int behindCount = counts[1];

                boolean isUpToDate = aheadCount == 0 && behindCount == 0 && !hasUnpublished && repoState == RepositoryState.SAFE
                        && !hasConflicts && !needsAuth;

                return new GitStatusInfo(isUpToDate, hasUnpublished, behindCount > 0, currentBranch,
                        aheadCount, behindCount, contentChanges, needsAuth, hasConflicts, repoState.name(),
                        conflictFiles, isSsh);
            }
        } catch (Exception e) {
            return handleStatusFailure(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Calculates ahead and behind counts, falling back to origin remote if no upstream is configured.
     *
     * @param repository the JGit repository
     * @param branchName the branch name to check
     * @return an array where [0] is ahead count and [1] is behind count
     * @throws IOException if Git operations fail
     */
    private int[] getAheadBehindCounts(Repository repository, String branchName) throws IOException {
        BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, branchName);
        if (trackingStatus != null) {
            return new int[] { trackingStatus.getAheadCount(), trackingStatus.getBehindCount() };
        }

        Ref remoteRef = repository.findRef("refs/remotes/origin/" + branchName);
        if (remoteRef == null) {
            return new int[] { 0, 0 };
        }

        ObjectId localHead = repository.resolve(branchName);
        ObjectId remoteHead = remoteRef.getObjectId();

        if (localHead == null || remoteHead == null) {
            return new int[] { 0, 0 };
        }

        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit localCommit = walk.parseCommit(localHead);
            RevCommit remoteCommit = walk.parseCommit(remoteHead);

            int ahead = 0;
            walk.reset();
            walk.markStart(localCommit);
            walk.markUninteresting(remoteCommit);
            for (RevCommit commit : walk) {
                ahead++;
            }

            int behind = 0;
            walk.reset();
            walk.markStart(remoteCommit);
            walk.markUninteresting(localCommit);
            for (RevCommit commit : walk) {
                behind++;
            }
            return new int[] { ahead, behind };
        }
    }

    /**
     * Synchronizes the local repository with the remote (git pull).
     * If there are local changes, it uses stash to ensure a clean pull.
     *
     * @return the result of the synchronization operation
     */
    @Override
    public GitSyncResult sync() {
        lock.lock();
        try (Repository repository = openRepository()) {
            if (repository == null) {
                return new GitSyncResult(false, MSG_REPO_NOT_FOUND, false, Collections.emptyList(), false);
            }
            try (Git git = new Git(repository)) {
                return doSync(git, repository);
            }
        } catch (Exception e) {
            return handleSyncFailure(e);
        } finally {
            lock.unlock();
        }
    }

    private GitSyncResult doSync(Git git, Repository repository) throws Exception {
        boolean wasDirty = !git.status().call().isClean();
        if (wasDirty) {
            LOG.debug(MSG_STASH_BEFORE_SYNC);
            git.stashCreate().setIncludeUntracked(true).call();
        }

        GitSyncResult syncResult = performPull(git, repository);

        if (wasDirty) {
            syncResult = operationHelper.restoreStash(git, syncResult, MSG_RESTORE_STASH_AFTER_SYNC);
        }
        return syncResult;
    }

    /**
     * Pushes local commits to the remote repository.
     *
     * @return the result of the push operation
     */
    @Override
    public GitSyncResult push() {
        lock.lock();
        try (Repository repository = openRepository()) {
            if (repository == null) {
                return new GitSyncResult(false, MSG_REPO_NOT_FOUND, false, Collections.emptyList(), false);
            }
            try (Git git = new Git(repository)) {
                return doPush(git, repository);
            }
        } catch (Exception e) {
            return handlePushFailure(e);
        } finally {
            lock.unlock();
        }
    }

    private GitSyncResult doPush(Git git, Repository repository) throws Exception {
        RepositoryState state = repository.getRepositoryState();
        if (state != RepositoryState.SAFE) {
            return new GitSyncResult(false,
                    "Push blocked: Repository is in state " + state + ". Please finalize your merge/rebase first.",
                    false, Collections.emptyList(), false);
        }

        Iterable<PushResult> results = configureTransport(
                git.push().setRemote("origin"), repository).call();

        for (PushResult pushResult : results) {
            for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    return new GitSyncResult(false,
                            "Push failed: " + update.getStatus() + " (" + update.getMessage() + ")",
                            false, Collections.emptyList(), false);
                }
            }
        }

        autoConfigureTracking(repository);
        return new GitSyncResult(true, MSG_PUSH_SUCCESS, false, Collections.emptyList(), false);
    }

    /**
     * Publishes changes by staging, committing, and pushing to the remote repository.
     * Dispatches between direct push (legacy) and PR-based flow based on configured
     * {@link Mode}.
     */
    @Override
    public GitSyncResult publish(String commitMessage, List<String> filePaths, String branchNameOverride) {
        lock.lock();
        try (Repository repository = openRepository()) {
            if (repository == null) {
                return new GitSyncResult(false, MSG_REPO_NOT_FOUND, false, Collections.emptyList(), false);
            }

            try (Git git = new Git(repository)) {
                if (editorConfig.sync().mode() == Mode.DIRECT) {
                    return doDirectPublish(git, repository, commitMessage, filePaths);
                }
                return doPrFlowPublish(git, repository, commitMessage, filePaths, branchNameOverride);
            }
        } catch (Exception e) {
            return handlePublishFailure(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public GitSyncResult publishAndSync(String commitMessage, List<String> filePaths, String branchNameOverride) {
        GitSyncResult publishResult = publish(commitMessage, filePaths, branchNameOverride);
        if (!publishResult.success())
            return publishResult;
        return sync();
    }

    /**
     * Original direct-push flow: stage, commit, optionally merge if behind, then push to the current branch.
     */
    private GitSyncResult doDirectPublish(Git git, Repository repository, String commitMessage, List<String> filePaths)
            throws Exception {
        operationHelper.stageChanges(git, repository, filePaths);

        GitSyncResult stateResult = operationHelper.finalizeState(git, repository, commitMessage);
        if (stateResult != null) {
            return stateResult;
        }

        tryFetch(git, GitTransportHelper.isSsh(repository));

        BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, repository.getBranch());
        if (trackingStatus != null && trackingStatus.getBehindCount() > 0
                && repository.getRepositoryState() == RepositoryState.SAFE) {
            LOG.debug(MSG_AUTO_MERGE_DURING_PUBLISH);
            GitSyncResult syncResult = doSync(git, repository);
            if (!syncResult.success()) {
                return syncResult;
            }
        }

        RepositoryState state = repository.getRepositoryState();
        if (state != RepositoryState.SAFE) {
            return new GitSyncResult(true,
                    "Partial resolution successful (State: " + state + "). Continue resolving/publishing.", false,
                    Collections.emptyList(), false);
        }

        return doPush(git, repository);
    }

    /**
     * PR-based flow. The current branch determines the action:
     * <ul>
     * <li>On the main branch: stage, create a content branch, commit, push -u, surface PR link.</li>
     * <li>On a content branch with the remote ref still present: stage, commit (or amend), push.</li>
     * <li>On a content branch whose remote ref was pruned (PR merged): stash, return to main, pull,
     * pop stash, and start a fresh content-branch cycle.</li>
     * <li>On any other branch: fall back to the direct-push flow.</li>
     * </ul>
     */
    private GitSyncResult doPrFlowPublish(Git git, Repository repository, String commitMessage, List<String> filePaths,
            String branchNameOverride) throws Exception {
        String mainBranch = resolveMainBranch(repository);
        String currentBranch = repository.getBranch();
        String prefix = editorConfig.sync().prFlow().contentBranchPrefix();

        if (!currentBranch.equals(mainBranch) && currentBranch.startsWith(prefix)) {
            fetchAndPrune(git, repository);
            if (remoteBranchExists(repository, currentBranch)) {
                return publishOnContentBranch(git, repository, commitMessage, filePaths, currentBranch);
            }
            GitSyncResult switched = switchBackToMain(git, repository, mainBranch);
            if (!switched.success()) {
                return switched;
            }
            currentBranch = mainBranch;
        }

        if (currentBranch.equals(mainBranch)) {
            return startContentBranchCycle(git, repository, commitMessage, filePaths, branchNameOverride);
        }

        LOG.debug("PR mode: current branch '" + currentBranch
                + "' is neither the main branch nor a content branch; falling back to direct publish");
        return doDirectPublish(git, repository, commitMessage, filePaths);
    }

    /**
     * Stages pending content edits, branches off main, commits, and pushes with upstream tracking.
     * Extracts a PR-creation URL from the remote messages when the host provides one.
     */
    private GitSyncResult startContentBranchCycle(Git git, Repository repository, String commitMessage,
            List<String> filePaths, String branchNameOverride) throws Exception {
        operationHelper.stageChanges(git, repository, filePaths);
        if (!operationHelper.hasStagedChanges(git) && repository.getRepositoryState() == RepositoryState.SAFE) {
            return new GitSyncResult(true, MSG_NOTHING_TO_PUBLISH, false, Collections.emptyList(), false);
        }

        String newBranch = resolveNewBranchName(repository, branchNameOverride);
        git.checkout().setCreateBranch(true).setName(newBranch).call();

        GitSyncResult stateResult = operationHelper.finalizeState(git, repository, commitMessage);
        if (stateResult != null) {
            return stateResult;
        }

        return pushContentBranch(git, repository, newBranch, false);
    }

    /**
     * Updates an existing content branch with a new commit (or amends the previous one) and pushes.
     */
    private GitSyncResult publishOnContentBranch(Git git, Repository repository, String commitMessage,
            List<String> filePaths, String branchName) throws Exception {
        operationHelper.stageChanges(git, repository, filePaths);

        CommitStrategy strategy = editorConfig.sync().prFlow().commitStrategy();
        boolean amend = strategy == CommitStrategy.AMEND
                && hasCommitsBeyond(repository, branchName, resolveMainBranch(repository));

        if (operationHelper.hasStagedChanges(git) || repository.getRepositoryState() == RepositoryState.MERGING) {
            String msg = (commitMessage == null || commitMessage.isBlank())
                    ? editorConfig.sync().commitMessage().template()
                    : commitMessage;
            git.commit().setMessage(msg).setAmend(amend).call();
        } else if (repository.getRepositoryState().isRebasing()) {
            GitSyncResult stateResult = operationHelper.finalizeState(git, repository, commitMessage);
            if (stateResult != null) {
                return stateResult;
            }
        } else {
            return new GitSyncResult(true, MSG_NOTHING_TO_PUBLISH, false, Collections.emptyList(), false);
        }

        return pushContentBranch(git, repository, branchName, amend);
    }

    private GitSyncResult pushContentBranch(Git git, Repository repository, String branchName, boolean force)
            throws Exception {
        Iterable<PushResult> results = configureTransport(
                git.push().setRemote("origin").setForce(force).add("refs/heads/" + branchName),
                repository).call();

        String prUrl = null;
        for (PushResult pushResult : results) {
            for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                RemoteRefUpdate.Status updateStatus = update.getStatus();
                if (updateStatus != RemoteRefUpdate.Status.OK
                        && updateStatus != RemoteRefUpdate.Status.UP_TO_DATE) {
                    return new GitSyncResult(false,
                            "Push failed: " + updateStatus + " (" + update.getMessage() + ")",
                            false, Collections.emptyList(), false);
                }
            }
            String messages = pushResult.getMessages();
            if (prUrl == null && messages != null) {
                prUrl = extractPrCreationUrl(messages);
            }
        }

        configureBranchTracking(repository, branchName);
        return new GitSyncResult(true, MSG_PUSH_SUCCESS, false, Collections.emptyList(), false, prUrl, branchName);
    }

    /**
     * Returns the editor to the main branch after the content branch's remote ref disappears
     * (typically because the PR was merged or closed). Local edits are preserved via stash.
     */
    private GitSyncResult switchBackToMain(Git git, Repository repository, String mainBranch) throws Exception {
        boolean wasDirty = !git.status().call().isClean();
        if (wasDirty) {
            git.stashCreate().setIncludeUntracked(true).call();
        }

        git.checkout().setName(mainBranch).call();

        GitSyncResult pullResult = performPull(git, repository);
        if (!pullResult.success()) {
            if (wasDirty) {
                operationHelper.restoreStash(git, pullResult, MSG_RESTORE_STASH_AFTER_SYNC);
            }
            return pullResult;
        }

        if (wasDirty) {
            return operationHelper.restoreStash(git, pullResult, MSG_RESTORE_STASH_AFTER_SYNC);
        }
        return pullResult;
    }

    private void fetchAndPrune(Git git, Repository repository) {
        try {
            configureTransport(git.fetch().setRemote("origin").setRemoveDeletedRefs(true), repository).call();
        } catch (Exception e) {
            LOG.debug("Fetch+prune failed (will continue with cached refs): " + e.getMessage());
        }
    }

    private boolean remoteBranchExists(Repository repository, String branchName) throws IOException {
        return repository.findRef("refs/remotes/origin/" + branchName) != null;
    }

    /**
     * Returns true when {@code branchName} contains at least one commit not reachable from
     * {@code baseBranch} — i.e., there is something on this content branch worth amending.
     */
    private boolean hasCommitsBeyond(Repository repository, String branchName, String baseBranch) throws IOException {
        ObjectId branchHead = repository.resolve(branchName);
        ObjectId baseHead = repository.resolve(baseBranch);
        if (branchHead == null || baseHead == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(branchHead));
            walk.markUninteresting(walk.parseCommit(baseHead));
            return walk.iterator().hasNext();
        }
    }

    private String resolveMainBranch(Repository repository) throws IOException {
        return editorConfig.sync().prFlow().mainBranch()
                .filter(b -> !b.isBlank())
                .orElseGet(() -> detectDefaultBranch(repository));
    }

    private String detectDefaultBranch(Repository repository) {
        try {
            Ref head = repository.findRef("refs/remotes/origin/HEAD");
            if (head != null && head.isSymbolic()) {
                String target = head.getTarget().getName();
                String prefix = "refs/remotes/origin/";
                if (target.startsWith(prefix)) {
                    return target.substring(prefix.length());
                }
            }
            if (repository.findRef("refs/heads/main") != null) {
                return "main";
            }
            if (repository.findRef("refs/heads/master") != null) {
                return "master";
            }
        } catch (IOException ignored) {
        }
        return "main";
    }

    private String resolveNewBranchName(Repository repository, String override) throws IOException {
        String prefix = editorConfig.sync().prFlow().contentBranchPrefix();
        String base;
        if (override != null && !override.isBlank()) {
            String sanitized = override.replaceAll("[^A-Za-z0-9._/-]", "-");
            base = sanitized.startsWith(prefix) ? sanitized : prefix + sanitized;
        } else {
            base = prefix + "update-" + LocalDateTime.now().format(BRANCH_TIMESTAMP);
        }

        String candidate = base;
        int suffix = 2;
        while (branchNameTaken(repository, candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private boolean branchNameTaken(Repository repository, String name) throws IOException {
        return repository.findRef("refs/heads/" + name) != null
                || repository.findRef("refs/remotes/origin/" + name) != null;
    }

    private void configureBranchTracking(Repository repository, String branchName) throws IOException {
        StoredConfig config = repository.getConfig();
        if (config.getString("branch", branchName, "remote") == null) {
            config.setString("branch", branchName, "remote", "origin");
            config.setString("branch", branchName, "merge", "refs/heads/" + branchName);
            config.save();
        }
    }

    static String extractPrCreationUrl(String pushMessages) {
        if (pushMessages == null || pushMessages.isEmpty()) {
            return null;
        }
        Matcher matcher = PR_URL_PATTERN.matcher(pushMessages);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Attempts to fetch from remote, handling authentication errors specifically for SSH.
     *
     * @param git the Git instance
     * @param isSsh true if the remote is an SSH URL
     * @return true if authentication failed
     */
    protected boolean tryFetch(Git git, boolean isSsh) {
        try {
            performFetch(git);
            return false;
        } catch (Exception fetchEx) {
            if (GitTransportHelper.isAuthenticationError(fetchEx) && isSsh) {
                LOG.warn("SSH authentication failed during fetch: " + fetchEx.getMessage());
                return true;
            }
            if (GitTransportHelper.isAuthenticationError(fetchEx)) {
                credentialHelper.invalidate();
            }
            LOG.debug("Fetch failed (will retry on next poll): " + fetchEx.getMessage());
            return false;
        }
    }

    /**
     * Handles failures during status retrieval, attempting to return as much information as possible.
     *
     * @param e the exception that occurred
     * @return an object containing as much status information as could be retrieved
     */
    private GitStatusInfo handleStatusFailure(Exception e) {
        boolean isSsh = resolveIsSsh();

        boolean authFailed = GitTransportHelper.isAuthenticationError(e) && isSsh;
        if (authFailed) {
            LOG.warn("SSH authentication failed: " + e.getMessage());
        } else if (!GitTransportHelper.isAuthenticationError(e)) {
            LOG.error("Failed to retrieve Git repository status", e);
        }

        try (Repository repository = openRepository()) {
            if (repository != null) {
                try (Git git = new Git(repository)) {
                    Status status = git.status().call();
                    String currentBranch = repository.getBranch();
                    List<String> conflictFiles = new ArrayList<>(status.getConflicting());
                    return new GitStatusInfo(false, false, false, currentBranch, 0, 0, Collections.emptyList(), authFailed,
                            !conflictFiles.isEmpty(), repository.getRepositoryState().name(), conflictFiles, isSsh);
                }
            }
            return new GitStatusInfo(false, false, false, "", 0, 0, Collections.emptyList(), authFailed, false, "ERROR",
                    Collections.emptyList(), isSsh);
        } catch (Exception inner) {
            return new GitStatusInfo(false, false, false, "", 0, 0, Collections.emptyList(), authFailed, false, "ERROR",
                    Collections.emptyList(), isSsh);
        }
    }

    /**
     * Performs a git pull and handles the result or potential authentication errors.
     *
     * @param git the Git instance
     * @param repository the JGit repository
     * @return the result of the pull operation
     */
    private GitSyncResult performPull(Git git, Repository repository) {
        try {
            PullResult pullResult = configureTransport(
                    git.pull().setRemote("origin").setRemoteBranchName(repository.getBranch()),
                    repository).call();

            if (!pullResult.isSuccessful()) {
                return operationHelper.handleFailedPull(pullResult, git);
            }

            autoConfigureTracking(repository);
            return new GitSyncResult(true, MSG_SYNC_SUCCESS, false, Collections.emptyList(), false);
        } catch (Exception e) {
            return handleRemoteFailure("Sync", e);
        }
    }

    private GitSyncResult handleSyncFailure(Exception e) {
        return handleRemoteFailure("Sync", e);
    }

    private GitSyncResult handlePushFailure(Exception e) {
        return handleRemoteFailure("Push", e);
    }

    private GitSyncResult handlePublishFailure(Exception e) {
        return handleRemoteFailure("Publish", e);
    }

    private void autoConfigureTracking(Repository repository) throws IOException {
        if (BranchTrackingStatus.of(repository, repository.getBranch()) == null) {
            StoredConfig config = repository.getConfig();
            String branchName = repository.getBranch();
            config.setString("branch", branchName, "remote", "origin");
            config.setString("branch", branchName, "merge", "refs/heads/" + branchName);
            config.save();
        }
    }

    /**
     * Opens the Git repository in the working directory.
     *
     * @return the JGit repository or null if not found
     * @throws IOException if an error occurs while opening the repository
     */
    private Repository openRepository() throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().readEnvironment().findGitDir(this.rootDirectory);
        if (builder.getGitDir() == null)
            return null;
        return builder.build();
    }

    /**
     * Performs a fetch operation from the remote repository.
     *
     * @param git the Git instance
     * @throws Exception if an error occurs during fetch
     */
    private void performFetch(Git git) throws Exception {
        LOG.debug("Performing Git fetch from origin...");
        configureTransport(git.fetch().setRemote("origin"), git.getRepository()).call();
    }

    private <C extends TransportCommand<C, ?>> C configureTransport(C cmd, Repository repository) {
        cmd.setTransportConfigCallback(GitTransportHelper.createTransportCallback(configuredPassphrase));
        if (!GitTransportHelper.isSsh(repository)) {
            CredentialsProvider cp = credentialHelper.getCredentials(repository);
            if (cp != null) {
                cmd.setCredentialsProvider(cp);
            }
        }
        return cmd;
    }

    private GitSyncResult handleRemoteFailure(String operation, Exception e) {
        boolean isSsh = resolveIsSsh();
        boolean isAuth = GitTransportHelper.isAuthenticationError(e);
        if (isAuth && isSsh) {
            LOG.warn(operation + " SSH authentication failed: " + e.getMessage());
            return new GitSyncResult(false, GitTransportHelper.ERR_AUTH_FAILED, false, Collections.emptyList(), true);
        }
        if (isAuth) {
            credentialHelper.invalidate();
        }
        LOG.error(operation + " failed", e);
        return new GitSyncResult(false, operation + " failed: " + e.getMessage(), false, Collections.emptyList(), false);
    }

    private boolean resolveIsSsh() {
        if (cachedIsSsh != null) {
            return cachedIsSsh;
        }
        try (Repository repository = openRepository()) {
            if (repository != null) {
                cachedIsSsh = GitTransportHelper.isSsh(repository);
                return cachedIsSsh;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
