/**
 * Toast Notification System
 * Provides non-intrusive user feedback for actions and events
 */

class ToastManager {
    constructor() {
        this.toasts = [];
        this.maxVisible = 3;
        this.queue = [];
        this.container = null;
        this.init();
    }

    /**
     * Initialize toast container and ARIA live region
     */
    init() {
        // Create container if it doesn't exist
        if (!document.getElementById('toast-container')) {
            this.container = document.createElement('div');
            this.container.id = 'toast-container';
            this.container.className = 'toast-container';
            this.container.setAttribute('aria-live', 'polite');
            this.container.setAttribute('aria-atomic', 'false');
            document.body.appendChild(this.container);
        } else {
            this.container = document.getElementById('toast-container');
        }
    }

    /**
     * Show a toast notification
     * @param {string} message - The message to display
     * @param {string} type - Type of toast: 'success', 'info', 'warning', 'error'
     * @param {number} duration - Duration in ms (0 = persistent)
     * @param {Array} actions - Array of action objects: {label, onClick, isPrimary}
     * @returns {string} Toast ID for programmatic dismissal
     */
    showToast(message, type = 'info', duration = 3000, actions = []) {
        const toast = {
            id: this.generateId(),
            message,
            type,
            duration,
            actions,
            timestamp: Date.now()
        };

        // If at max capacity, queue it
        if (this.toasts.length >= this.maxVisible) {
            this.queue.push(toast);
            return toast.id;
        }

        this.displayToast(toast);
        return toast.id;
    }

    /**
     * Display a toast on screen
     * @param {Object} toast - Toast object
     */
    displayToast(toast) {
        this.toasts.push(toast);

        // Create toast element
        const toastEl = document.createElement('div');
        toastEl.className = `toast toast-${toast.type}`;
        toastEl.id = `toast-${toast.id}`;
        toastEl.setAttribute('role', 'status');
        toastEl.setAttribute('aria-live', 'polite');

        // Icon based on type
        const icon = this.getIcon(toast.type);

        // Build toast HTML
        toastEl.innerHTML = `
            <div class="toast-icon">${icon}</div>
            <div class="toast-content">
                <div class="toast-message">${this.escapeHtml(toast.message)}</div>
                ${toast.actions.length > 0 ? this.buildActions(toast) : ''}
            </div>
            <button class="toast-close" aria-label="Close notification" data-toast-id="${toast.id}">
                <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
                    <path d="M8 7.293l3.646-3.647a.5.5 0 01.708.708L8.707 8l3.647 3.646a.5.5 0 01-.708.708L8 8.707l-3.646 3.647a.5.5 0 01-.708-.708L7.293 8 3.646 4.354a.5.5 0 01.708-.708L8 7.293z"/>
                </svg>
            </button>
            ${toast.duration > 0 ? '<div class="toast-progress"></div>' : ''}
        `;

        // Add to container with animation
        this.container.appendChild(toastEl);

        // Trigger animation
        setTimeout(() => toastEl.classList.add('toast-visible'), 10);

        // Attach event listeners
        this.attachEventListeners(toastEl, toast);

        // Auto-dismiss if duration set
        if (toast.duration > 0) {
            this.startProgressBar(toastEl, toast);
            toast.timeoutId = setTimeout(() => {
                this.dismissToast(toast.id);
            }, toast.duration);
        }
    }

    /**
     * Build action buttons HTML
     * @param {Object} toast - Toast object
     * @returns {string} HTML string
     */
    buildActions(toast) {
        const actionsHtml = toast.actions.map((action, index) => {
            const btnClass = action.isPrimary ? 'toast-action-primary' : 'toast-action';
            return `<button class="${btnClass}" data-action-index="${index}" data-toast-id="${toast.id}">
                ${this.escapeHtml(action.label)}
            </button>`;
        }).join('');

        return `<div class="toast-actions">${actionsHtml}</div>`;
    }

    /**
     * Attach event listeners to toast element
     * @param {HTMLElement} toastEl - Toast DOM element
     * @param {Object} toast - Toast object
     */
    attachEventListeners(toastEl, toast) {
        // Close button
        const closeBtn = toastEl.querySelector('.toast-close');
        closeBtn.addEventListener('click', () => this.dismissToast(toast.id));

        // Action buttons
        const actionBtns = toastEl.querySelectorAll('[data-action-index]');
        actionBtns.forEach((btn, index) => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const action = toast.actions[index];
                if (action.onClick) {
                    action.onClick();
                }
                // Auto-dismiss after action
                this.dismissToast(toast.id);
            });
        });

        // Swipe to dismiss on mobile
        this.addSwipeSupport(toastEl, toast.id);

        // Pause auto-dismiss on hover/focus
        if (toast.duration > 0) {
            toastEl.addEventListener('mouseenter', () => this.pauseToast(toast));
            toastEl.addEventListener('mouseleave', () => this.resumeToast(toast));
            toastEl.addEventListener('focusin', () => this.pauseToast(toast));
            toastEl.addEventListener('focusout', () => this.resumeToast(toast));
        }
    }

    /**
     * Add swipe-to-dismiss support for mobile
     * @param {HTMLElement} toastEl - Toast DOM element
     * @param {string} toastId - Toast ID
     */
    addSwipeSupport(toastEl, toastId) {
        let startX = 0;
        let currentX = 0;

        toastEl.addEventListener('touchstart', (e) => {
            startX = e.touches[0].clientX;
            toastEl.style.transition = 'none';
        });

        toastEl.addEventListener('touchmove', (e) => {
            currentX = e.touches[0].clientX;
            const diffX = currentX - startX;

            // Only allow right swipe
            if (diffX > 0) {
                toastEl.style.transform = `translateX(${diffX}px)`;
            }
        });

        toastEl.addEventListener('touchend', () => {
            const diffX = currentX - startX;
            toastEl.style.transition = '';
            toastEl.style.transform = '';

            // If swiped more than 100px, dismiss
            if (diffX > 100) {
                this.dismissToast(toastId);
            }
        });
    }

    /**
     * Start progress bar animation
     * @param {HTMLElement} toastEl - Toast DOM element
     * @param {Object} toast - Toast object
     */
    startProgressBar(toastEl, toast) {
        const progressBar = toastEl.querySelector('.toast-progress');
        if (progressBar) {
            progressBar.style.animation = `toast-progress ${toast.duration}ms linear`;
        }
    }

    /**
     * Pause auto-dismiss
     * @param {Object} toast - Toast object
     */
    pauseToast(toast) {
        if (toast.timeoutId) {
            clearTimeout(toast.timeoutId);
            toast.pausedAt = Date.now();

            // Pause progress bar
            const toastEl = document.getElementById(`toast-${toast.id}`);
            const progressBar = toastEl?.querySelector('.toast-progress');
            if (progressBar) {
                progressBar.style.animationPlayState = 'paused';
            }
        }
    }

    /**
     * Resume auto-dismiss
     * @param {Object} toast - Toast object
     */
    resumeToast(toast) {
        if (toast.pausedAt) {
            const remainingTime = toast.duration - (toast.pausedAt - toast.timestamp);
            toast.timestamp = Date.now();
            toast.duration = remainingTime;
            toast.pausedAt = null;

            // Resume progress bar
            const toastEl = document.getElementById(`toast-${toast.id}`);
            const progressBar = toastEl?.querySelector('.toast-progress');
            if (progressBar) {
                progressBar.style.animationPlayState = 'running';
            }

            toast.timeoutId = setTimeout(() => {
                this.dismissToast(toast.id);
            }, remainingTime);
        }
    }

    /**
     * Dismiss a toast notification
     * @param {string} toastId - Toast ID to dismiss
     */
    dismissToast(toastId) {
        const toast = this.toasts.find(t => t.id === toastId);
        if (!toast) return;

        // Clear timeout
        if (toast.timeoutId) {
            clearTimeout(toast.timeoutId);
        }

        // Remove from DOM with animation
        const toastEl = document.getElementById(`toast-${toastId}`);
        if (toastEl) {
            toastEl.classList.remove('toast-visible');
            toastEl.classList.add('toast-dismissing');

            setTimeout(() => {
                toastEl.remove();

                // Remove from array
                this.toasts = this.toasts.filter(t => t.id !== toastId);

                // Show next queued toast
                if (this.queue.length > 0) {
                    const nextToast = this.queue.shift();
                    this.displayToast(nextToast);
                }
            }, 300); // Match CSS transition duration
        }
    }

    /**
     * Dismiss all toasts
     */
    dismissAll() {
        const toastIds = this.toasts.map(t => t.id);
        toastIds.forEach(id => this.dismissToast(id));
        this.queue = [];
    }

    /**
     * Get icon SVG for toast type
     * @param {string} type - Toast type
     * @returns {string} SVG HTML
     */
    getIcon(type) {
        const icons = {
            success: `<svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
            </svg>`,
            error: `<svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/>
            </svg>`,
            warning: `<svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd"/>
            </svg>`,
            info: `<svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd"/>
            </svg>`
        };
        return icons[type] || icons.info;
    }

    /**
     * Escape HTML to prevent XSS
     * @param {string} text - Text to escape
     * @returns {string} Escaped text
     */
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    /**
     * Generate unique toast ID
     * @returns {string} Unique ID
     */
    generateId() {
        return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    }
}

// Create global toast manager instance
const toastManager = new ToastManager();

/**
 * Global helper function to show toast
 * @param {string} message - Message to display
 * @param {string} type - Toast type: 'success', 'info', 'warning', 'error'
 * @param {number} duration - Duration in ms (0 = persistent)
 * @param {Array} actions - Array of action objects
 * @returns {string} Toast ID
 */
function showToast(message, type = 'info', duration = 3000, actions = []) {
    return toastManager.showToast(message, type, duration, actions);
}

/**
 * Helper to show success toast
 * @param {string} message - Success message
 * @param {number} duration - Duration in ms
 * @returns {string} Toast ID
 */
function showSuccess(message, duration = 3000) {
    return showToast(message, 'success', duration);
}

/**
 * Helper to show error toast
 * @param {string} message - Error message
 * @param {number} duration - Duration in ms (0 = persistent for errors)
 * @returns {string} Toast ID
 */
function showError(message, duration = 0) {
    return showToast(message, 'error', duration);
}

/**
 * Helper to show warning toast
 * @param {string} message - Warning message
 * @param {number} duration - Duration in ms
 * @returns {string} Toast ID
 */
function showWarning(message, duration = 4000) {
    return showToast(message, 'warning', duration);
}

/**
 * Helper to show info toast
 * @param {string} message - Info message
 * @param {number} duration - Duration in ms
 * @returns {string} Toast ID
 */
function showInfo(message, duration = 3000) {
    return showToast(message, 'info', duration);
}

/**
 * Dismiss a specific toast
 * @param {string} toastId - Toast ID to dismiss
 */
function dismissToast(toastId) {
    toastManager.dismissToast(toastId);
}

/**
 * Dismiss all toasts
 */
function dismissAllToasts() {
    toastManager.dismissAll();
}

// Export for module usage
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        ToastManager,
        showToast,
        showSuccess,
        showError,
        showWarning,
        showInfo,
        dismissToast,
        dismissAllToasts
    };
}
