import { LitElement, html, css } from 'lit';
import '@vaadin/icon';

export class SyncStatusBar extends LitElement {

    static properties = {
        status: { type: Object },
        syncing: { type: Boolean }
    };

    static styles = css`
        :host {
            display: flex;
            align-items: center;
            gap: var(--lumo-space-s);
            padding: var(--lumo-space-xs) var(--lumo-space-s);
            background: var(--lumo-contrast-5pct);
            border-radius: var(--lumo-border-radius-m);
            font-size: var(--lumo-font-size-xs);
            transition: background 0.3s ease;
        }

        :host(:hover) {
            background: var(--lumo-contrast-10pct);
        }

        .status-icon {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 16px;
            height: 16px;
            flex-shrink: 0;
        }

        .status-icon vaadin-icon {
            width: 14px;
            height: 14px;
        }

        /* Semantic Colors */
        .up-to-date { color: var(--lumo-success-color); }
        .unpublished { color: var(--lumo-warning-color); }
        .remote-changes { color: var(--lumo-primary-color); }
        .diverged { color: #9c27b0; } 
        .conflict { color: var(--lumo-error-color); }
        .syncing { color: var(--lumo-contrast-40pct); }
        .auth { color: var(--lumo-error-color); }

        .pulse {
            animation: pulse 1.5s ease-in-out infinite;
        }

        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.6; transform: scale(0.9); }
        }

        .status-text {
            color: var(--lumo-secondary-text-color);
            white-space: nowrap;
            font-weight: 500;
        }

        .branch-info {
            display: flex;
            align-items: center;
            gap: var(--lumo-space-xs);
            margin-left: var(--lumo-space-s);
            padding-left: var(--lumo-space-s);
            border-left: 1px solid var(--lumo-contrast-10pct);
        }

        .branch-name {
            color: var(--lumo-contrast-80pct);
            font-family: var(--lumo-font-family-mono);
            font-size: var(--lumo-font-size-xxs);
            padding: 2px 6px;
            background: var(--lumo-contrast-10pct);
            border-radius: var(--lumo-border-radius-s);
        }

        .ahead-behind {
            display: flex;
            gap: var(--lumo-space-xs);
            font-size: var(--lumo-font-size-xxs);
            font-weight: bold;
        }

        .ahead { color: var(--lumo-warning-text-color); }
        .behind { color: var(--lumo-primary-text-color); }

        .state-tag {
            font-size: 9px;
            text-transform: uppercase;
            padding: 1px 4px;
            background: var(--lumo-error-color-10pct);
            color: var(--lumo-error-color);
            border-radius: 4px;
            margin-left: 4px;
            font-weight: bold;
        }
    `;

    constructor() {
        super();
        this.status = null;
        this.syncing = false;
    }

    _renderStatus() {
        if (this.syncing) {
            return html`<vaadin-icon class="status-icon syncing pulse" icon="font-awesome-solid:rotate"></vaadin-icon>`;
        }
        
        if (!this.status) {
            return html`<div class="status-icon syncing pulse" style="background: var(--lumo-contrast-20pct); border-radius: 50%; width: 8px; height: 8px;"></div>`;
        }

        if (this.status.authFailed && this.status.isSsh) {
            return html`<vaadin-icon class="status-icon auth pulse" icon="font-awesome-solid:lock"></vaadin-icon>`;
        }

        if (this.status.hasConflicts) {
            return html`<vaadin-icon class="status-icon conflict" icon="font-awesome-solid:triangle-exclamation"></vaadin-icon>`;
        }

        if (this.status.ahead > 0 && this.status.behind > 0) {
            return html`<vaadin-icon class="status-icon diverged" icon="font-awesome-solid:arrows-rotate"></vaadin-icon>`;
        }

        if (this.status.behind > 0 || this.status.hasRemoteChanges) {
            return html`<vaadin-icon class="status-icon remote-changes" icon="font-awesome-solid:cloud-arrow-down"></vaadin-icon>`;
        }

        if (this.status.ahead > 0 || this.status.hasUnpublished) {
            return html`<vaadin-icon class="status-icon unpublished" icon="font-awesome-solid:cloud-arrow-up"></vaadin-icon>`;
        }

        if (this.status.upToDate) {
            return html`<div class="status-icon up-to-date" style="background: currentColor; border-radius: 50%; width: 8px; height: 8px; margin: 4px;"></div>`;
        }

        return html`<div class="status-icon syncing" style="background: var(--lumo-contrast-20pct); border-radius: 50%; width: 8px; height: 8px; margin: 4px;"></div>`;
    }

    _getStatusText() {
        if (this.syncing) return 'Syncing...';
        if (!this.status) return 'Checking status...';
        
        // Blockers first
        if (this.status.authFailed && this.status.isSsh) return 'SSH Authentication Required';
        if (this.status.hasConflicts) return 'Git Conflicts Detected';
        
        // Branch state
        if (this.status.ahead > 0 && this.status.behind > 0) return 'Branch Diverged';
        
        // Remote priority
        if (this.status.behind > 0 || this.status.hasRemoteChanges) return 'Remote changes detected';
        
        // Local attention
        if (this.status.hasUnpublished) return 'Content Not Published';
        if (this.status.ahead > 0) return 'Local commits pending';
        
        // Normal
        if (this.status.upToDate) return 'Content Up to date';
        
        return 'Ready';
    }

    render() {
        const repoState = this.status?.repositoryState;
        const isSafe = !repoState || repoState === 'SAFE';

        return html`
            <div class="status-icon ${this._getStatusClass()}">
                ${this._renderStatus()}
            </div>
            
            <span class="status-text">${this._getStatusText()}</span>
            
            ${!isSafe ? html`<span class="state-tag">${repoState.replace('_', ' ')}</span>` : ''}
            
            ${this.status?.branch && this.status.branch !== 'sync-disabled' ? html`
                <div class="branch-info">
                    <vaadin-icon icon="font-awesome-solid:code-branch" style="width: 12px; color: var(--lumo-contrast-50pct)"></vaadin-icon>
                    <span class="branch-name">${this.status.branch}</span>
                    ${this.status.ahead > 0 || this.status.behind > 0 ? html`
                        <div class="ahead-behind">
                            ${this.status.ahead > 0 ? html`
                                <span class="ahead" title="${this.status.ahead} commits ahead">↑${this.status.ahead}</span>
                            ` : ''}
                            ${this.status.behind > 0 ? html`
                                <span class="behind" title="${this.status.behind} commits behind">↓${this.status.behind}</span>
                            ` : ''}
                        </div>
                    ` : ''}
                </div>
            ` : ''}
        `;
    }

    _getStatusClass() {
        if (this.syncing) return 'syncing';
        if (!this.status) return 'syncing';
        if (this.status.authFailed && this.status.isSsh) return 'auth';
        if (this.status.hasConflicts) return 'conflict';
        if (this.status.ahead > 0 && this.status.behind > 0) return 'diverged';
        if (this.status.behind > 0 || this.status.hasRemoteChanges) return 'remote-changes';
        if (this.status.ahead > 0 || this.status.hasUnpublished) return 'unpublished';
        return 'up-to-date';
    }
}

customElements.define('qwc-sync-status-bar', SyncStatusBar);
