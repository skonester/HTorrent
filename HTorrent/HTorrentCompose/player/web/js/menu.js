// The right-click menu (also opened by the ⋮ button). Each submenu is its own panel, positioned to stay inside the
// window, and checkmarks show the current state of every toggle.
(function () {
    'use strict';

    const App = window.App;
    const { El, State, Utils } = App;
    const video = El.video;

    const SEP = { separator: true };
    const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
    const CHECK_SVG = '<svg viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></svg>';
    const DOT_SVG = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4.5"/></svg>';
    const ARROW_SVG = '<svg viewBox="0 0 24 24"><path d="M10 6 8.6 7.4 13.2 12l-4.6 4.6L10 18l6-6z"/></svg>';
    const EDGE = 6;
    const SWITCH_DELAY = 200;

    const panels = []; // open panels; index = depth
    let switchTimer = 0;
    let swallowClick = false;

    // Menu definition. label/checked/enabled may be functions so they reflect the state when the menu opens.
    // keepOpen items can be clicked repeatedly; radio items show a dot instead of a check.
    function menuItems() {
        const P = App.Player;
        const media = () => P.hasMedia();
        const visual = () => P.hasVideo();
        const filter = (label, name) => ({
            label, enabled: visual, checked: () => State.filters[name], keepOpen: true,
            action: () => P.toggleFilter(name, label.toUpperCase())
        });

        return [
            { label: () => (media() && !video.paused && !video.ended ? 'Pause' : 'Play'), hint: 'Space', action: P.togglePlay },
            { label: 'Stop', hint: 'S', enabled: media, action: P.stop },
            SEP,
            { label: 'Open File...', hint: 'O', action: P.openFileDialog },
            { label: 'Open URL...', hint: 'U', action: App.Dialogs.openUrl },
            { label: 'Close Media', enabled: media, action: P.close },
            SEP,
            {
                label: 'Playback', sub: () => [
                    { label: 'Forward 30 Seconds', hint: 'Shift+→', enabled: media, keepOpen: true, action: () => App.Timeline.accumulateSeek(30) },
                    { label: 'Back 30 Seconds', hint: 'Shift+←', enabled: media, keepOpen: true, action: () => App.Timeline.accumulateSeek(-30) },
                    SEP,
                    {
                        label: 'Speed', sub: () => SPEEDS.map(rate => ({
                            label: rate === 1 ? '1x (Normal)' : `${rate}x`, radio: true,
                            checked: () => Math.abs(video.playbackRate - rate) < 0.001,
                            action: () => P.setSpeed(rate)
                        }))
                    },
                    { label: 'Loop', hint: 'L', checked: () => video.loop, action: P.toggleLoop },
                    { label: 'Mute', hint: 'M', checked: () => video.muted, action: P.toggleMute }
                ]
            },
            {
                label: 'Video', sub: () => [
                    {
                        label: 'Aspect Ratio', enabled: visual, sub: () => Object.keys(P.ASPECTS).map(mode => ({
                            label: P.ASPECTS[mode].label, radio: true,
                            checked: () => State.aspect === mode,
                            action: () => P.setAspect(mode)
                        }))
                    },
                    SEP,
                    { label: 'Brightness Up', enabled: visual, keepOpen: true, action: () => P.adjustBrightness(0.1) },
                    { label: 'Brightness Down', enabled: visual, keepOpen: true, action: () => P.adjustBrightness(-0.1) },
                    SEP,
                    filter('Negative', 'invert'),
                    filter('Soften', 'soften'),
                    filter('Boost Contrast', 'contrast'),
                    filter('Flip Horizontal', 'flip'),
                    { label: 'Scanlines', enabled: visual, keepOpen: true, checked: () => document.body.classList.contains('scanlines'), action: P.toggleScanlines },
                    SEP,
                    { label: 'Reset Video', enabled: P.hasVideoAdjustments, action: P.resetVideo }
                ]
            },
            SEP,
            { label: 'Fullscreen', hint: 'F', checked: P.isFullscreen, action: P.toggleFullscreen },
            { label: 'Picture-in-Picture', hint: 'P', enabled: visual, checked: () => !!document.pictureInPictureElement, action: P.togglePiP },
            { label: 'Playback Stats', hint: 'I', checked: () => State.statsVisible, action: P.toggleStats },
            { label: 'Ambilight Glow', hint: 'A', checked: () => !!App.Settings.values.ambilight, action: P.toggleAmbilight },
            SEP,
            { label: 'Settings...', action: () => App.Dialogs.open('settingsModal') },
            { label: 'Keyboard Shortcuts', hint: '?', action: () => App.Dialogs.open('hotkeysModal') },
            { label: 'About HTorrentPlayer', action: () => App.Dialogs.open('aboutModal') },
            SEP,
            { label: 'Exit', action: P.exit }
        ];
    }

    const resolve = v => (typeof v === 'function' ? v() : v);

    function buildPanel(defs, depth) {
        const panel = document.createElement('div');
        panel.className = 'ctx-menu';
        panel.setAttribute('role', 'menu');

        defs.forEach(def => {
            if (def.separator) {
                panel.appendChild(Object.assign(document.createElement('div'), { className: 'ctx-sep' }));
                return;
            }
            const item = document.createElement('div');
            item.className = 'ctx-item';
            item.setAttribute('role', 'menuitem');
            item.innerHTML = '<span class="ctx-mark"></span><span class="ctx-label"></span><span class="ctx-hint"></span>' +
                (def.sub ? `<span class="ctx-arrow">${ARROW_SVG}</span>` : '');
            item.menuDef = def;
            item.addEventListener('mouseenter', () => onItemEnter(item, depth));
            item.addEventListener('click', e => {
                e.stopPropagation();
                onItemClick(item, depth);
            });
            panel.appendChild(item);
        });

        panel.addEventListener('mouseenter', () => clearTimeout(switchTimer));
        panel.addEventListener('mousedown', e => e.stopPropagation());
        panel.addEventListener('contextmenu', e => {
            e.preventDefault();
            e.stopPropagation();
        });
        panel.addEventListener('wheel', e => e.stopPropagation());

        refreshPanel(panel);
        El.menuLayer.appendChild(panel);
        return panel;
    }

    function refreshPanel(panel) {
        panel.querySelectorAll('.ctx-item').forEach(item => {
            const def = item.menuDef;
            const enabled = def.enabled ? !!def.enabled() : true;
            const checked = def.checked ? !!def.checked() : false;
            item.querySelector('.ctx-label').textContent = resolve(def.label);
            item.querySelector('.ctx-hint').textContent = def.hint || '';
            item.querySelector('.ctx-mark').innerHTML = checked ? (def.radio ? DOT_SVG : CHECK_SVG) : '';
            item.classList.toggle('disabled', !enabled);
            item.setAttribute('aria-disabled', String(!enabled));
            if (def.checked) item.setAttribute('aria-checked', String(checked));
        });
    }

    function place(panel, x, y) {
        const w = panel.offsetWidth;
        const h = panel.offsetHeight;
        panel.style.left = `${Utils.clamp(x, EDGE, Math.max(EDGE, window.innerWidth - w - EDGE))}px`;
        panel.style.top = `${Utils.clamp(y, EDGE, Math.max(EDGE, window.innerHeight - h - EDGE))}px`;
    }

    function closeFrom(depth) {
        while (panels.length > depth) {
            const panel = panels.pop();
            if (panel.owner) panel.owner.classList.remove('open');
            panel.remove();
        }
    }

    // Submenus open to the right of their item, or to the left when there is no room. Once a submenu has flipped left,
    // deeper ones keep going left so they never cover the menus they came from.
    function openSubmenu(item, depth) {
        closeFrom(depth + 1);
        const panel = buildPanel(resolve(item.menuDef.sub), depth + 1);
        panel.owner = item;
        item.classList.add('open');
        panels.push(panel);

        const itemRect = item.getBoundingClientRect();
        const parentRect = panels[depth].getBoundingClientRect();
        const width = panel.offsetWidth;
        const fitsRight = parentRect.right - 2 + width <= window.innerWidth - EDGE;
        const fitsLeft = parentRect.left + 2 - width >= EDGE;
        panel.direction = panels[depth].direction === 'left'
            ? (fitsLeft || !fitsRight ? 'left' : 'right')
            : (fitsRight || !fitsLeft ? 'right' : 'left');
        const x = panel.direction === 'right' ? parentRect.right - 2 : parentRect.left - width + 2;
        place(panel, x, itemRect.top - 7);
    }

    function onItemEnter(item, depth) {
        clearTimeout(switchTimer);
        const openChild = panels[depth + 1];
        if (openChild && openChild.owner === item) return;

        const act = () => {
            if (item.menuDef.sub && !item.classList.contains('disabled')) openSubmenu(item, depth);
            else closeFrom(depth + 1);
        };
        // A short delay lets the mouse cross other items on its way into an open submenu.
        if (openChild) switchTimer = setTimeout(act, SWITCH_DELAY);
        else act();
    }

    function onItemClick(item, depth) {
        const def = item.menuDef;
        if (item.classList.contains('disabled')) return;
        if (def.sub) {
            clearTimeout(switchTimer);
            const openChild = panels[depth + 1];
            if (!openChild || openChild.owner !== item) openSubmenu(item, depth);
            return;
        }
        if (def.keepOpen) {
            def.action();
            App.Menu.refresh();
            return;
        }
        App.Menu.close();
        def.action();
    }

    App.Menu = {
        isOpen: () => panels.length > 0,

        open(x, y) {
            App.Menu.close();
            const panel = buildPanel(menuItems(), 0);
            panels.push(panel);
            place(panel, x, y);
            App.OSD.resetUIHider();
        },

        // Opens above an element, aligned to its right edge (the ⋮ button on the control bar).
        openAbove(element) {
            App.Menu.open(0, 0);
            const panel = panels[0];
            const rect = element.getBoundingClientRect();
            place(panel, rect.right - panel.offsetWidth, rect.top - panel.offsetHeight - 10);
        },

        refresh() {
            panels.forEach(refreshPanel);
        },

        close() {
            clearTimeout(switchTimer);
            closeFrom(0);
        },

        init() {
            window.addEventListener('contextmenu', e => {
                e.preventDefault();
                if (App.Dialogs.anyOpen()) return;
                App.Menu.open(e.clientX, e.clientY);
            });

            El.menuBtn.addEventListener('click', e => {
                e.stopPropagation();
                if (App.Menu.isOpen()) App.Menu.close();
                else App.Menu.openAbove(El.menuBtn);
            });

            // A click outside only dismisses the menu; it does not also play/pause, seek or press a button underneath.
            // (Clicking ⋮ while the menu is open therefore just closes it.)
            document.addEventListener('mousedown', e => {
                if (!App.Menu.isOpen() || e.button !== 0 || El.menuLayer.contains(e.target)) return;
                e.stopPropagation();
                App.Menu.close();
                swallowClick = true;
            }, true);
            document.addEventListener('click', e => {
                if (!swallowClick) return;
                swallowClick = false;
                e.stopPropagation();
                e.preventDefault();
            }, true);
            document.addEventListener('mouseup', () => setTimeout(() => { swallowClick = false; }), true);

            window.addEventListener('resize', App.Menu.close);
            window.addEventListener('blur', App.Menu.close);
            document.addEventListener('wheel', () => App.Menu.close(), { passive: true });
        }
    };
})();
