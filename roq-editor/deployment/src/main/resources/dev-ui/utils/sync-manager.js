export class SyncManager {
    constructor(jsonRpc, onStatusChange, syncConfig, onPassphraseRequired) {
        this.jsonRpc = jsonRpc;
        this.onStatusChange = onStatusChange;
        this.syncConfig = syncConfig;
        this.onPassphraseRequired = onPassphraseRequired;
        this.status = null;
        this.passphrase = null;
        this.intervalId = null;
        this.pollingCount = 0;
    }

    start() {
        if (this.intervalId) return;
        
        // Polling interval for status updates (default 10s)
        const pollingIntervalMs = 10 * 1000;
        
        this.lastAutoSyncTime = Date.now();
        this.lastAutoPublishTime = Date.now();

        // First refresh should NOT skip fetch to detect auth needs immediately
        this.refreshStatus(false);
        
        this.intervalId = setInterval(async () => {
            this.pollingCount++;
            // Perform a full fetch every 6 cycles (approx 60s)
            const skipFetch = (this.pollingCount % 6 !== 0);
            const status = await this.refreshStatus(skipFetch);
            
            if (!status || status.hasConflicts) return;

            const now = Date.now();

            // 1. Auto-Sync Logic
            const autoSyncConfig = this.syncConfig?.['auto-sync'];
            if (autoSyncConfig?.enabled && status.behind > 0) {
                const syncIntervalMs = (autoSyncConfig['interval-seconds'] || 60) * 1000;
                if (now - this.lastAutoSyncTime >= syncIntervalMs) {
                    console.log("[SyncManager] Triggering auto-sync...");
                    try {
                        // For auto-sync, we only proceed if we have a passphrase for SSH or it's not SSH
                        if (!status.isSsh || this.passphrase) {
                            await this.manualSync();
                            this.lastAutoSyncTime = now;
                        }
                    } catch (e) {
                        console.warn("[SyncManager] Auto-sync failed:", e.message);
                    }
                }
            }

            // 2. Auto-Publish Logic
            const autoPublishConfig = this.syncConfig?.['auto-publish'];
            if (autoPublishConfig?.enabled && status.hasUnpublished) {
                const publishIntervalMs = (autoPublishConfig['interval-seconds'] || 300) * 1000;
                if (now - this.lastAutoPublishTime >= publishIntervalMs) {
                    console.log("[SyncManager] Triggering auto-publish...");
                    try {
                        // For auto-publish, we only proceed if we have a passphrase for SSH or it's not SSH
                        if (!status.isSsh || this.passphrase) {
                            const message = this.syncConfig?.['commit-message']?.template || "Auto-update via Roq Editor";
                            await this.manualPublish(message, status.pendingFiles || []);
                            this.lastAutoPublishTime = now;
                        }
                    } catch (e) {
                        console.warn("[SyncManager] Auto-publish failed:", e.message);
                    }
                }
            }
        }, pollingIntervalMs); 
    }

    stop() {
        if (this.intervalId) {
            clearInterval(this.intervalId);
            this.intervalId = null;
        }
    }

    async refreshStatus(skipFetch = true) {
        try {
            const currentPassphrase = this.passphrase;
            const response = await this.jsonRpc.getSyncStatus({ 
                passphrase: currentPassphrase, 
                skipFetch 
            });
            const statusInfo = response.result;
            if (!statusInfo) return;

            this.status = statusInfo;
            
            // Only prompt if backend explicitly says authFailed AND it's an SSH repo
            if (statusInfo.authFailed && statusInfo.isSsh) {
                console.warn("[SyncManager] Auth error detected for SSH repo, clearing passphrase and prompting");
                const alreadyTried = !!this.passphrase;
                this.passphrase = null;
                // Only show error if we had a passphrase and it failed
                this.onPassphraseRequired(alreadyTried ? "SSH authentication failed. Please check your passphrase." : null);
            }
            
            this.onStatusChange(statusInfo);
            return statusInfo;
        } catch (error) {
            console.error("[SyncManager] Failed to refresh Git status", error);
        }
    }

    setPassphrase(passphrase) {
        this.passphrase = passphrase;
        // Immediate full refresh to validate the new passphrase
        this.refreshStatus(false);
    }

    _isAuthError(errorOrMsg) {
        const msg = typeof errorOrMsg === 'string' ? errorOrMsg : (errorOrMsg?.message || "");
        // STRICT CHECK: Only trigger for our custom prefixes
        return msg.includes("AUTH_FAILED:") || msg.includes("AUTH_REQUIRED:");
    }

    async _withPassphrase(operation) {
        // Proactive check: Only for SSH
        if (!this.passphrase && this.status?.authFailed && this.status?.isSsh) {
            this.onPassphraseRequired(null);
            throw new Error("AUTH_REQUIRED: SSH passphrase required");
        }
        try {
            const result = await operation(this.passphrase);
            
            if (result?.authFailed && this.status?.isSsh) {
                const hadPassphrase = !!this.passphrase;
                this.passphrase = null;
                const errorMsg = result?.message || "Authentication failed";
                this.onPassphraseRequired(hadPassphrase ? errorMsg.replace("AUTH_FAILED:", "").replace("AUTH_REQUIRED:", "") : null);
                throw new Error(errorMsg.startsWith("AUTH_") ? errorMsg : `AUTH_FAILED:${errorMsg}`);
            }
            return result;
        } catch (error) {
            if (this._isAuthError(error)) {
                const hadPassphrase = !!this.passphrase;
                this.passphrase = null;
                const msg = typeof error === 'string' ? error : error.message;
                this.onPassphraseRequired(hadPassphrase ? msg.replace("AUTH_FAILED:", "").replace("AUTH_REQUIRED:", "") : null);
            }
            throw error;
        }
    }

    async manualSync() {
        const result = await this._withPassphrase(
            (pass) => this.jsonRpc.syncContent({ passphrase: pass }).then(r => r.result)
        );
        await this.refreshStatus(true);
        return result;
    }

    async manualPublish(message, filePaths) {
        const result = await this._withPassphrase(
            (pass) => this.jsonRpc.publishContent({ message, passphrase: pass, filePaths: filePaths ?? [] }).then(r => r.result)
        );
        await this.refreshStatus(true);
        return result;
    }

    async publishAndSync(message, filePaths) {
        const result = await this._withPassphrase(
            (pass) => this.jsonRpc.publishAndSync({ message, passphrase: pass, filePaths: filePaths ?? [] }).then(r => r.result)
        );
        await this.refreshStatus(true);
        return result;
    }
}
