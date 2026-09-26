// On-screen messages, the seek overlay, the status line and auto-hiding controls.
(function () {
    'use strict';

    const App = window.App;
    const { El, State, Utils } = App;

    let osdTimeout = 0;
    let uiTimeout = 0;

    const ARROW_RIGHT = '<svg viewBox="0 0 24 24"><path d="M8 6v12l8.5-6L8 6z"/></svg>';
    const ARROW_LEFT = '<svg viewBox="0 0 24 24"><path d="M16 18V6l-8.5 6 8.5 6z"/></svg>';

    App.OSD = {
        show(title, main, sub = '') {
            El.osdTitle.textContent = title;
            El.osdMain.textContent = main;
            El.osdSub.textContent = sub;
            El.osdAlert.classList.add('show');
            clearTimeout(osdTimeout);
            osdTimeout = setTimeout(() => El.osdAlert.classList.remove('show'), 2200);
        },

        // Short status next to the title in the top banner (CONNECTING, BUFFERING, PLAYING...).
        setStatus(text, isError = false) {
            El.npStatus.textContent = text;
            El.npStatus.classList.toggle('error', isError);
        },

        showSeekOverlay(direction, seconds) {
            const side = direction > 0 ? 'right' : 'left';
            if (!El.seekOverlay.classList.contains(side) || !El.seekOverlay.classList.contains('show')) {
                El.seekOverlay.classList.remove('left', 'right');
                El.seekOverlay.classList.add(side, 'show');
                El.seekArrows.className = `seek-arrows ${side}`;
                El.seekArrows.innerHTML = (direction > 0 ? ARROW_RIGHT : ARROW_LEFT).repeat(3);
            }
            El.seekText.textContent = `${direction > 0 ? '+' : '-'}${Utils.formatSeekDelta(seconds)}`;
        },

        hideSeekOverlay() {
            El.seekOverlay.classList.remove('show');
        },

        hideUI() {
            El.playerContainer.classList.add('hide-ui');
            document.body.classList.add('hide-cursor');
            El.timelineTooltip.classList.remove('show');
        },

        showUI() {
            El.playerContainer.classList.remove('hide-ui');
            document.body.classList.remove('hide-cursor');
        },

        // Shows the controls and hides them again after the configured delay while media plays,
        // unless the mouse is over them or a menu, dialog or drag is in progress.
        resetUIHider(e) {
            if (e && e.type === 'mousemove') {
                if (e.clientX === State.lastMouseX && e.clientY === State.lastMouseY) return;
                State.lastMouseX = e.clientX;
                State.lastMouseY = e.clientY;
            }

            App.OSD.showUI();
            clearTimeout(uiTimeout);

            const delay = App.Settings.values.hideDelay;
            if (!State.source || El.video.paused || !delay) return;

            uiTimeout = setTimeout(() => {
                const busy = El.controls.matches(':hover') ||
                    App.Menu.isOpen() ||
                    App.Dialogs.anyOpen() ||
                    State.scrub.down ||
                    State.accum.active;
                if (busy) App.OSD.resetUIHider();
                else if (!El.video.paused) App.OSD.hideUI();
            }, delay);
        }
    };
})();
