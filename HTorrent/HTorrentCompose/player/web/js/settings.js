// Saved preferences and the in-page dialogs (settings, shortcuts, open URL, about).
(function () {
    'use strict';

    const App = window.App;
    const { El, Utils } = App;

    const SETTINGS_KEY = 'htorrentPlayerSettings';
    const DEFAULTS = {
        accent: '#ff88bb',
        controlsOpacity: 0.8,
        controlsBlur: 24,
        hideDelay: 3000,
        ambilight: true,
        resume: true,
        volume: 1,
        muted: false
    };

    App.Settings = {
        values: { ...DEFAULTS },

        load() {
            App.Settings.values = { ...DEFAULTS, ...Utils.loadJson(SETTINGS_KEY, {}) };
            App.Settings.apply();
            App.Settings.syncForm();
        },

        save() {
            Utils.saveJson(SETTINGS_KEY, App.Settings.values);
        },

        set(changes) {
            Object.assign(App.Settings.values, changes);
            App.Settings.save();
            App.Settings.apply();
            App.Settings.syncForm();
        },

        apply() {
            const s = App.Settings.values;
            const root = document.documentElement.style;
            root.setProperty('--accent', s.accent);
            root.setProperty('--controls-opacity', s.controlsOpacity);
            root.setProperty('--controls-blur', `${s.controlsBlur}px`);
            document.body.classList.toggle('ambilight', !!s.ambilight);
        },

        syncForm() {
            const s = App.Settings.values;
            El.accentColorPicker.value = s.accent;
            El.controlsOpacitySlider.value = s.controlsOpacity;
            El.controlsBlurSlider.value = s.controlsBlur;
            El.hideDelaySelect.value = String(s.hideDelay);
            El.ambilightToggle.checked = !!s.ambilight;
            El.resumeToggle.checked = !!s.resume;
        },

        init() {
            El.accentColorPicker.addEventListener('input', () => App.Settings.set({ accent: El.accentColorPicker.value }));
            El.accentResetBtn.addEventListener('click', () => App.Settings.set({ accent: DEFAULTS.accent }));
            El.controlsOpacitySlider.addEventListener('input', () => App.Settings.set({ controlsOpacity: parseFloat(El.controlsOpacitySlider.value) }));
            El.controlsBlurSlider.addEventListener('input', () => App.Settings.set({ controlsBlur: parseInt(El.controlsBlurSlider.value, 10) }));
            El.hideDelaySelect.addEventListener('change', () => App.Settings.set({ hideDelay: parseInt(El.hideDelaySelect.value, 10) }));
            El.ambilightToggle.addEventListener('change', () => App.Settings.set({ ambilight: El.ambilightToggle.checked }));
            El.resumeToggle.addEventListener('change', () => App.Settings.set({ resume: El.resumeToggle.checked }));
            El.openHotkeysBtn.addEventListener('click', () => App.Dialogs.open('hotkeysModal'));
        }
    };

    const MODALS = ['settingsModal', 'hotkeysModal', 'urlModal', 'aboutModal'];

    App.Dialogs = {
        open(id) {
            App.Menu.close();
            MODALS.forEach(m => El[m].classList.toggle('show', m === id));
            App.OSD.resetUIHider();
        },

        close(id) {
            El[id].classList.remove('show');
            App.OSD.resetUIHider();
        },

        closeAll() {
            const wasOpen = App.Dialogs.anyOpen();
            MODALS.forEach(m => El[m].classList.remove('show'));
            if (wasOpen) App.OSD.resetUIHider();
            return wasOpen;
        },

        anyOpen() {
            return MODALS.some(m => El[m].classList.contains('show'));
        },

        toggle(id) {
            if (El[id].classList.contains('show')) App.Dialogs.close(id);
            else App.Dialogs.open(id);
        },

        openUrl() {
            El.urlError.textContent = '';
            App.Dialogs.open('urlModal');
            El.urlInput.focus();
            El.urlInput.select();
        },

        init() {
            MODALS.forEach(id => {
                const modal = El[id];
                // Clicking the dimmed backdrop or any close button closes the dialog.
                modal.addEventListener('mousedown', e => { if (e.target === modal) App.Dialogs.close(id); });
                modal.querySelectorAll('.modal-close').forEach(btn => btn.addEventListener('click', () => App.Dialogs.close(id)));
            });

            El.urlForm.addEventListener('submit', e => {
                e.preventDefault();
                const value = El.urlInput.value.trim();
                let url;
                try { url = new URL(value); } catch (err) { url = null; }
                if (!url || (url.protocol !== 'http:' && url.protocol !== 'https:')) {
                    El.urlError.textContent = 'Enter a full http:// or https:// address.';
                    return;
                }
                App.Dialogs.close('urlModal');
                App.Player.loadUrl(url.href);
            });
        }
    };
})();
