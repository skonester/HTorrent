// Cached elements and shared player state.
(function () {
    'use strict';

    const App = window.App;

    // Every element with an id, e.g. App.El.video, App.El.progressBar. The scripts load at the end of <body>.
    App.El = {};
    document.querySelectorAll('[id]').forEach(node => { App.El[node.id] = node; });

    App.State = {
        // { kind: 'file' | 'url', url, name, key } for the loaded media, or null
        source: null,
        objectUrl: null,
        subUrl: null,
        isAudio: false,
        stopRequested: false,

        // Timeline
        scrub: { down: false, dragging: false, startX: 0, startY: 0, lastSeek: 0 },
        accum: { active: false, base: 0, offset: 0, timer: 0 },
        progressRect: null,

        // Video adjustments from the right-click menu
        filters: { brightness: 1, invert: false, soften: false, contrast: false, flip: false },
        aspect: 'original',

        statsVisible: false,
        lastVolume: 1,
        lastMouseX: -1,
        lastMouseY: -1
    };
})();
