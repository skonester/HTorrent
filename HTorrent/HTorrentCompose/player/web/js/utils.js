// Shared helpers. The player interface is adapted from Perdanga VSP (MIT, see licenses/Perdanga-VSP-LICENSE.txt).
(function () {
    'use strict';

    const App = window.App = window.App || {};

    App.Utils = {
        clamp(value, min, max) {
            return Math.min(max, Math.max(min, value));
        },

        formatTime(secs) {
            if (!Number.isFinite(secs) || secs < 0) return '00:00';
            const h = Math.floor(secs / 3600);
            const m = Math.floor((secs % 3600) / 60);
            const s = Math.floor(secs % 60);
            const mm = String(m).padStart(2, '0');
            const ss = String(s).padStart(2, '0');
            return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
        },

        formatDetailedTime(secs) {
            if (!Number.isFinite(secs) || secs < 0) return '00:00:00.000';
            const h = Math.floor(secs / 3600);
            const m = Math.floor((secs % 3600) / 60);
            const s = Math.floor(secs % 60);
            const ms = Math.floor((secs % 1) * 1000);
            return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(ms).padStart(3, '0')}`;
        },

        formatSeekDelta(seconds) {
            const abs = Math.round(Math.abs(seconds));
            const h = Math.floor(abs / 3600);
            const m = Math.floor((abs % 3600) / 60);
            const s = abs % 60;
            const parts = [];
            if (h > 0) parts.push(`${h}h`);
            if (m > 0) parts.push(`${m}m`);
            if (s > 0 || parts.length === 0) parts.push(`${s}s`);
            return parts.join(' ');
        },

        // 1 -> "1.0x", 1.5 -> "1.5x", 1.25 -> "1.25x"
        formatRate(rate) {
            return (Math.round(rate * 100) / 100).toFixed(2).replace(/0$/, '') + 'x';
        },

        // localStorage lives in the WebView2 profile (%LOCALAPPDATA%\HTorrent\HTorrentPlayer); it can be unavailable.
        loadJson(key, fallback) {
            try {
                const value = JSON.parse(localStorage.getItem(key));
                return value && typeof value === 'object' ? value : fallback;
            } catch (err) {
                return fallback;
            }
        },

        saveJson(key, value) {
            try { localStorage.setItem(key, JSON.stringify(value)); } catch (err) { /* storage unavailable */ }
        },

        // Window actions handled by PlayerWindow.fs. Returns false outside WebView2 (e.g. when testing in a browser).
        postHostAction(action, extra = {}) {
            try {
                if (!(window.chrome && window.chrome.webview)) return false;
                window.chrome.webview.postMessage(JSON.stringify({ action, ...extra }));
                return true;
            } catch (err) {
                return false;
            }
        }
    };
})();
