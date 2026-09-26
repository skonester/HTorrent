// Startup.
(function () {
    'use strict';

    const App = window.App;

    App.Settings.init();
    App.Settings.load();
    App.Dialogs.init();
    App.Player.init();
    App.Timeline.init();
    App.Menu.init();

    // Buttons give focus back after a click, so Space always means play/pause.
    document.addEventListener('click', e => {
        const button = e.target.closest('button');
        if (button && !button.closest('.modal')) button.blur();
    });

    // HTorrent opens the player with index.html?src=<stream url>&title=<file name>.
    const params = new URLSearchParams(location.search);
    const src = params.get('src');
    if (src) App.Player.loadUrl(src, params.get('title'));

    App.OSD.resetUIHider();
})();
