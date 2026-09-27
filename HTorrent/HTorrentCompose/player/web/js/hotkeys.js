// Keyboard shortcuts (listed in the Keyboard Shortcuts dialog in index.html).
(function () {
    'use strict';

    const App = window.App;

    window.addEventListener('keydown', e => {
        if (e.code === 'Escape') {
            if (App.Menu.isOpen()) App.Menu.close();
            else if (!App.Dialogs.closeAll() && App.Player.isFullscreen()) App.Player.toggleFullscreen();
            return;
        }
        // Dialogs keep their keys (typing a URL, arrow keys on sliders).
        if (App.Dialogs.anyOpen() || e.ctrlKey || e.altKey || e.metaKey) return;

        const P = App.Player;
        App.Menu.close();
        App.OSD.resetUIHider();

        if (e.key === '?') {
            App.Dialogs.open('hotkeysModal');
            return;
        }

        switch (e.code) {
            case 'Space':
                e.preventDefault(); // otherwise a focused button would also be pressed
                if (!e.repeat) P.togglePlay();
                break;
            case 'ArrowLeft':
                e.preventDefault();
                App.Timeline.accumulateSeek(e.shiftKey ? -30 : -5);
                break;
            case 'ArrowRight':
                e.preventDefault();
                App.Timeline.accumulateSeek(e.shiftKey ? 30 : 5);
                break;
            case 'ArrowUp':
                e.preventDefault();
                P.changeVolume(0.05);
                break;
            case 'ArrowDown':
                e.preventDefault();
                P.changeVolume(-0.05);
                break;
            case 'Comma':
                P.setSpeed(App.El.video.playbackRate - 0.25);
                break;
            case 'Period':
                P.setSpeed(App.El.video.playbackRate + 0.25);
                break;
            default:
                if (e.repeat) return;
                switch (e.code) {
                    case 'KeyS': P.stop(); break;
                    case 'KeyF': P.toggleFullscreen(); break;
                    case 'KeyM': P.toggleMute(); break;
                    case 'KeyL': P.toggleLoop(); break;
                    case 'KeyP': P.togglePiP(); break;
                    case 'KeyI': P.toggleStats(); break;
                    case 'KeyA': P.toggleAmbilight(); break;
                    case 'KeyO': P.openFileDialog(); break;
                    case 'KeyU': App.Dialogs.openUrl(); e.preventDefault(); break;
                }
        }
    });
})();
