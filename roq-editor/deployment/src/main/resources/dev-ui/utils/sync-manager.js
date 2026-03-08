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
        
        const autoSyncConfig = this.syncConfig?.['auto-sync'];
        const intervalMs = (autoSyncConfig?.['interval-seconds'] || 10) * 1000;
        
        // First refresh should NOT skip fetch to detect auth needs immediately
        this.refreshStatus(false);
        
        this.intervalId = setInterval(() => {
            this.pollingCount++;
            // Perform a full fetch every 6 cycles (approx 60s if interval is 10s)
            const skipFetch = (this.pollingCount % 6 !== 0);
            this.refreshStatus(skipFetch);
        }, intervalMs); 
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
                this.passphrase = null;
                this.onPassphraseRequired();
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
            this.onPassphraseRequired();
            throw new Error("AUTH_REQUIRED: SSH passphrase required");
        }
        try {
            const result = await operation(this.passphrase);
            
            if (result?.authFailed && this.status?.isSsh) {
                this.passphrase = null;
                this.onPassphraseRequired();
                const errorMsg = result?.message || "Authentication failed";
                throw new Error(errorMsg.startsWith("AUTH_") ? errorMsg : `AUTH_FAILED:${errorMsg}`);
            }
            return result;
        } catch (error) {
            if (this._isAuthError(error)) {
                this.passphrase = null;
                this.onPassphraseRequired();
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
