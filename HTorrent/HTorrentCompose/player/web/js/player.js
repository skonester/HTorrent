// Loading media, playback, volume and speed, video adjustments, ambilight and playback stats.
(function () {
    'use strict';

    const App = window.App;
    const { El, State, Utils } = App;
    const video = El.video;

    const PROGRESS_KEY = 'htorrentPlayerProgress';
    const PROGRESS_LIMIT = 50;
    const AUDIO_EXT = /\.(mp3|m4a|aac|flac|ogg|oga|opus|wav|weba)$/i;
    const DEFAULT_FILTERS = { brightness: 1, invert: false, soften: false, contrast: false, flip: false };
    const ASPECTS = {
        original: { label: 'Original' },
        '4:3': { label: '4:3', ratio: 4 / 3 },
        '16:9': { label: '16:9', ratio: 16 / 9 },
        '21:9': { label: '21:9', ratio: 21 / 9 },
        fill: { label: 'Fill Window (crop)' },
        stretch: { label: 'Stretch to Window' }
    };
    const READY_STATES = ['nothing', 'metadata', 'current data', 'future data', 'enough data'];

    let progressCache = null;
    let lastProgressSave = 0;
    let clickTimer = 0;
    let statsTimer = 0;
    let lastStatsTime = 0;
    let lastStatsFrames = 0;
    let estimatedFps = 0;

    const ambilightCtx = El.ambilight.getContext('2d');

    // HTorrent streams are http://127.0.0.1:<port>/torrents/<info hash>/stream/<file index>. The port can change
    // between runs, so saved positions are keyed by the path.
    function progressKeyForUrl(url) {
        try {
            const u = new URL(url);
            if ((u.hostname === '127.0.0.1' || u.hostname === 'localhost') && u.pathname.startsWith('/torrents/')) return `torrent:${u.pathname}`;
            return u.href;
        } catch (err) {
            return url;
        }
    }

    function nameFromUrl(url) {
        try {
            return decodeURIComponent(new URL(url).pathname.split('/').pop()) || url;
        } catch (err) {
            return url;
        }
    }

    function progressStore() {
        if (!progressCache) progressCache = Utils.loadJson(PROGRESS_KEY, {});
        return progressCache;
    }

    function setBuffering(on) {
        El.playerContainer.classList.toggle('buffering', on);
    }

    function setPlayIcon(playing) {
        El.playIcon.style.display = playing ? 'none' : 'block';
        El.pauseIcon.style.display = playing ? 'block' : 'none';
        El.playerContainer.classList.toggle('playing', playing);
    }

    App.Player = {
        ASPECTS,

        hasMedia: () => !!State.source,
        hasVideo: () => !!State.source && !State.isAudio,
        isLocal: () => !!State.source && State.source.kind === 'file',

        // ---------------------------------------------------------------- loading

        loadFile(file) {
            if (!file) return;
            const objectUrl = URL.createObjectURL(file);
            App.Player.begin(
                { kind: 'file', url: objectUrl, name: file.name, key: `file:${file.name}:${file.size}` },
                file.type.startsWith('audio') || AUDIO_EXT.test(file.name));
            State.objectUrl = objectUrl;
        },

        // Network sources, including HTorrent's torrent streams.
        loadUrl(url, title) {
            const name = title || nameFromUrl(url);
            App.Player.begin({ kind: 'url', url, name, key: progressKeyForUrl(url) }, AUDIO_EXT.test(name));
        },

        begin(source, audioHint) {
            App.Player.unload();
            State.source = source;
            State.stopRequested = false;
            El.playerContainer.classList.add('has-media');
            App.Player.setAudioMode(audioHint);
            App.Player.setTitle(source.name);
            App.OSD.setStatus(source.kind === 'url' ? 'CONNECTING' : 'LOADING');
            setBuffering(true);
            video.src = source.url;
            App.Timeline.onSourceChanged();
            video.play().catch(() => {});
            App.OSD.resetUIHider();
        },

        // Releases the current media (blob URLs, subtitles, pending seeks) without touching the rest of the UI.
        unload() {
            clearTimeout(State.accum.timer);
            State.accum.active = false;
            App.OSD.hideSeekOverlay();
            App.Player.removeSubtitles();
            if (State.objectUrl) {
                URL.revokeObjectURL(State.objectUrl);
                State.objectUrl = null;
            }
            State.source = null;
        },

        close() {
            if (!State.source) return;
            App.Player.unload();
            video.pause();
            video.removeAttribute('src');
            video.load();
            State.isAudio = false;
            El.playerContainer.classList.remove('has-media', 'audio-mode', 'playing', 'buffering');
            setPlayIcon(false);
            App.Player.setTitle('');
            App.OSD.setStatus('');
            App.Timeline.onSourceChanged();
            ambilightCtx.clearRect(0, 0, El.ambilight.width, El.ambilight.height);
            if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
            App.OSD.resetUIHider();
        },

        openFileDialog() {
            El.fileInput.click();
        },

        setTitle(title) {
            El.npFilename.textContent = title;
            document.title = title ? `${title} - HTorrentPlayer` : 'HTorrentPlayer';
            Utils.postHostAction('setTitle', { title });
        },

        setAudioMode(isAudio) {
            State.isAudio = isAudio;
            El.playerContainer.classList.toggle('audio-mode', isAudio);
            El.pipBtn.disabled = isAudio;
        },

        // ---------------------------------------------------------------- playback

        togglePlay() {
            if (!State.source) {
                App.Player.openFileDialog();
                return;
            }
            if (video.paused || video.ended) {
                State.stopRequested = false;
                video.play().catch(() => {});
            } else {
                video.pause();
            }
        },

        stop() {
            if (!State.source) return;
            State.stopRequested = true;
            video.pause();
            video.currentTime = 0;
            App.Player.clearProgress();
            App.OSD.setStatus('STOPPED');
        },

        toggleLoop() {
            video.loop = !video.loop;
            El.loopBtn.classList.toggle('active', video.loop);
            App.OSD.show('LOOP', video.loop ? 'On' : 'Off');
        },

        setSpeed(rate) {
            rate = Utils.clamp(Math.round(rate * 100) / 100, 0.25, 4);
            video.defaultPlaybackRate = rate; // carries over to the next file
            video.playbackRate = rate;
            App.OSD.show('SPEED', Utils.formatRate(rate));
        },

        // ---------------------------------------------------------------- volume

        setVolume(value) {
            value = Utils.clamp(Math.round(value * 100) / 100, 0, 1);
            video.volume = value;
            video.muted = value === 0;
            if (value > 0) State.lastVolume = value;
            App.OSD.show('VOLUME', `${Math.round(value * 100)}%`);
        },

        changeVolume(delta) {
            App.Player.setVolume((video.muted ? 0 : video.volume) + delta);
        },

        toggleMute() {
            if (video.muted || video.volume === 0) {
                if (video.volume === 0) video.volume = State.lastVolume || 1;
                video.muted = false;
                App.OSD.show('VOLUME', `${Math.round(video.volume * 100)}%`);
            } else {
                video.muted = true;
                App.OSD.show('VOLUME', 'Muted');
            }
        },

        updateVolumeUI() {
            const shown = video.muted ? 0 : video.volume;
            El.volumeSlider.value = shown;
            El.volumeSlider.style.setProperty('--vol-pct', `${shown * 100}%`);
            El.volUpIcon.style.display = shown === 0 ? 'none' : 'block';
            El.volMuteIcon.style.display = shown === 0 ? 'block' : 'none';
        },

        // ---------------------------------------------------------------- window

        isFullscreen: () => !!document.fullscreenElement,

        toggleFullscreen() {
            if (document.fullscreenElement) {
                document.exitFullscreen().catch(() => {});
            } else {
                document.documentElement.requestFullscreen().catch(() => App.OSD.show('FULLSCREEN', 'Not available right now'));
            }
        },

        togglePiP() {
            if (document.pictureInPictureElement) {
                document.exitPictureInPicture().catch(() => {});
            } else if (!App.Player.hasVideo()) {
                App.OSD.show('PICTURE-IN-PICTURE', 'Load a video first');
            } else if (!document.pictureInPictureEnabled) {
                App.OSD.show('PICTURE-IN-PICTURE', 'Not supported here');
            } else if (video.readyState < 1) {
                App.OSD.show('PICTURE-IN-PICTURE', 'The video is still loading');
            } else {
                video.requestPictureInPicture().catch(() => App.OSD.show('PICTURE-IN-PICTURE', 'Could not open the mini player'));
            }
        },

        exit() {
            if (!Utils.postHostAction('close')) window.close();
        },

        // ---------------------------------------------------------------- video adjustments

        applyVideoAdjustments() {
            const f = State.filters;
            const parts = [];
            if (f.brightness !== 1) parts.push(`brightness(${f.brightness})`);
            if (f.invert) parts.push('invert(1)');
            if (f.soften) parts.push('blur(2px)');
            if (f.contrast) parts.push('contrast(1.35) saturate(1.1)');
            video.style.filter = parts.join(' ');
            video.style.transform = f.flip ? 'scaleX(-1)' : '';
        },

        adjustBrightness(delta) {
            const f = State.filters;
            f.brightness = Utils.clamp(Math.round((f.brightness + delta) * 10) / 10, 0.3, 2);
            App.Player.applyVideoAdjustments();
            App.OSD.show('BRIGHTNESS', `${Math.round(f.brightness * 100)}%`);
        },

        toggleFilter(name, label) {
            State.filters[name] = !State.filters[name];
            App.Player.applyVideoAdjustments();
            App.OSD.show(label, State.filters[name] ? 'On' : 'Off');
        },

        toggleScanlines() {
            const on = document.body.classList.toggle('scanlines');
            App.OSD.show('SCANLINES', on ? 'On' : 'Off');
        },

        hasVideoAdjustments() {
            const f = State.filters;
            return f.brightness !== 1 || f.invert || f.soften || f.contrast || f.flip ||
                document.body.classList.contains('scanlines') || State.aspect !== 'original';
        },

        resetVideo() {
            State.filters = { ...DEFAULT_FILTERS };
            document.body.classList.remove('scanlines');
            App.Player.applyVideoAdjustments();
            App.Player.setAspect('original', false);
            App.OSD.show('VIDEO', 'Adjustments reset');
        },

        // Width and height are both 100% by default, so a fixed ratio needs an explicit size that fits the window.
        setAspect(mode, announce = true) {
            const aspect = ASPECTS[mode] || ASPECTS.original;
            State.aspect = ASPECTS[mode] ? mode : 'original';
            const s = video.style;
            s.width = s.height = s.aspectRatio = s.objectFit = '';
            if (mode === 'fill') {
                s.objectFit = 'cover';
            } else if (mode === 'stretch') {
                s.objectFit = 'fill';
            } else if (aspect.ratio) {
                s.objectFit = 'fill';
                s.aspectRatio = String(aspect.ratio);
                s.width = `min(100%, calc(100vh * ${aspect.ratio}))`;
                s.height = 'auto';
            }
            if (announce) App.OSD.show('ASPECT RATIO', aspect.label);
        },

        toggleAmbilight() {
            App.Settings.set({ ambilight: !App.Settings.values.ambilight });
            App.OSD.show('AMBILIGHT', App.Settings.values.ambilight ? 'On' : 'Off');
        },

        drawAmbilight() {
            if (!App.Settings.values.ambilight || State.isAudio || !State.source || video.readyState < 2) return;
            try { ambilightCtx.drawImage(video, 0, 0, El.ambilight.width, El.ambilight.height); } catch (err) { /* frame not ready */ }
        },

        // ---------------------------------------------------------------- stats

        toggleStats() {
            State.statsVisible = !State.statsVisible;
            El.statsOverlay.classList.toggle('show', State.statsVisible);
            clearInterval(statsTimer);
            if (State.statsVisible) {
                lastStatsTime = 0;
                App.Player.updateStats();
                statsTimer = setInterval(App.Player.updateStats, 500);
            }
        },

        updateStats() {
            const quality = typeof video.getVideoPlaybackQuality === 'function' ? video.getVideoPlaybackQuality() : null;
            const frames = quality ? quality.totalVideoFrames : 0;
            const now = performance.now();
            if (lastStatsTime && frames >= lastStatsFrames) estimatedFps = ((frames - lastStatsFrames) * 1000) / (now - lastStatsTime);
            lastStatsTime = now;
            lastStatsFrames = frames;

            let ahead = 0;
            for (let i = 0; i < video.buffered.length; i++) {
                if (video.buffered.start(i) <= video.currentTime && video.currentTime <= video.buffered.end(i)) ahead = video.buffered.end(i) - video.currentTime;
            }
            const source = !State.source ? 'none' : (State.source.kind === 'url' ? 'stream' : 'local file');
            El.statsOverlay.textContent = [
                `source      ${source}`,
                `resolution  ${video.videoWidth || 0}x${video.videoHeight || 0}`,
                `fps         ${estimatedFps && !video.paused ? estimatedFps.toFixed(1) : 'n/a'}`,
                `dropped     ${quality ? quality.droppedVideoFrames : 0} of ${frames}`,
                `buffered    ${ahead.toFixed(1)}s ahead`,
                `speed       ${Utils.formatRate(video.playbackRate || 1)}`,
                `volume      ${video.muted ? 'muted' : Math.round(video.volume * 100) + '%'}`,
                `ready       ${READY_STATES[video.readyState] || video.readyState}`
            ].join('\n');
        },

        // ---------------------------------------------------------------- resume position

        savedPosition() {
            if (!App.Settings.values.resume || !State.source) return 0;
            const time = progressStore()[State.source.key] || 0;
            const duration = video.duration;
            return time > 5 && Number.isFinite(duration) && time < duration - 10 ? time : 0;
        },

        saveProgress() {
            if (!App.Settings.values.resume || !State.source || video.currentTime < 5) return;
            const now = performance.now();
            if (now - lastProgressSave < 4000) return;
            lastProgressSave = now;
            const store = progressStore();
            delete store[State.source.key]; // re-insert so the oldest entries are dropped first
            store[State.source.key] = video.currentTime;
            const keys = Object.keys(store);
            keys.slice(0, Math.max(0, keys.length - PROGRESS_LIMIT)).forEach(k => delete store[k]);
            Utils.saveJson(PROGRESS_KEY, store);
        },

        clearProgress() {
            if (!State.source) return;
            const store = progressStore();
            if (!(State.source.key in store)) return;
            delete store[State.source.key];
            Utils.saveJson(PROGRESS_KEY, store);
        },

        // ---------------------------------------------------------------- subtitles (see milestone.md)

        loadSubtitleFile(file) {
            App.Player.removeSubtitles();
            State.subUrl = URL.createObjectURL(file);
            const track = document.createElement('track');
            track.kind = 'captions';
            track.label = 'Custom Subtitles';
            track.srclang = 'en';
            track.src = State.subUrl;
            track.default = true;
            video.appendChild(track);
            App.OSD.show('SUBTITLES', 'Loaded', file.name);
        },

        removeSubtitles() {
            video.querySelectorAll('track').forEach(t => t.remove());
            if (State.subUrl) {
                URL.revokeObjectURL(State.subUrl);
                State.subUrl = null;
            }
        },

        // ---------------------------------------------------------------- events

        onLoadedMetadata() {
            App.Player.setAudioMode(video.videoWidth === 0 && video.videoHeight === 0);
            App.Timeline.onDurationChanged();
            const saved = App.Player.savedPosition();
            if (saved) {
                video.currentTime = saved;
                App.OSD.show('RESUMED', `From ${Utils.formatTime(saved)}`, 'Press S to start over');
            }
        },

        onError() {
            if (!State.source || !video.getAttribute('src')) return;
            setBuffering(false);
            setPlayIcon(false);
            const code = video.error ? video.error.code : 0;
            if (code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
                App.OSD.setStatus('FORMAT NOT SUPPORTED', true);
                App.OSD.show('CAN\'T PLAY THIS FILE', 'Format not supported', 'MP4 (H.264/AAC) and WebM play best');
            } else if (State.source.kind === 'url') {
                App.OSD.setStatus('STREAM ERROR', true);
                App.OSD.show('STREAM ERROR', 'The stream stopped', 'Check that HTorrent is still running');
            } else {
                App.OSD.setStatus('PLAYBACK ERROR', true);
                App.OSD.show('PLAYBACK ERROR', 'The file could not be decoded');
            }
        },

        init() {
            const s = App.Settings.values;
            video.volume = Utils.clamp(Number(s.volume) || 0, 0, 1);
            video.muted = !!s.muted;
            if (video.volume > 0) State.lastVolume = video.volume;
            App.Player.updateVolumeUI();

            // Playback state. The icons and status follow the media events, so a stream that is still
            // buffering (or a play() the browser rejected) never shows as playing.
            video.addEventListener('loadedmetadata', App.Player.onLoadedMetadata);
            video.addEventListener('durationchange', App.Timeline.onDurationChanged);
            video.addEventListener('play', () => {
                setPlayIcon(true);
                App.OSD.resetUIHider();
            });
            video.addEventListener('pause', () => {
                if (!State.source) return;
                setPlayIcon(false);
                if (!video.seeking) setBuffering(false);
                if (!video.ended) App.OSD.setStatus(State.stopRequested ? 'STOPPED' : 'PAUSED');
                App.OSD.resetUIHider();
            });
            video.addEventListener('playing', () => {
                State.stopRequested = false;
                setBuffering(false);
                App.OSD.setStatus('PLAYING');
            });
            video.addEventListener('waiting', () => {
                if (!State.source) return;
                setBuffering(true);
                App.OSD.setStatus('BUFFERING');
            });
            // Seeking a torrent stream can wait on pieces HTorrent hasn't downloaded yet.
            video.addEventListener('seeking', () => { if (State.source && State.source.kind === 'url') setBuffering(true); });
            video.addEventListener('seeked', () => {
                if (video.paused) setBuffering(false);
                App.Player.drawAmbilight();
            });
            video.addEventListener('canplay', () => { if (video.paused) setBuffering(false); });
            video.addEventListener('loadeddata', App.Player.drawAmbilight);
            video.addEventListener('ended', () => {
                setBuffering(false);
                setPlayIcon(false);
                App.Player.clearProgress();
                App.OSD.setStatus('FINISHED');
                App.OSD.resetUIHider();
            });
            video.addEventListener('error', App.Player.onError);
            video.addEventListener('timeupdate', () => {
                App.Timeline.onTimeUpdate();
                App.Player.saveProgress();
            });
            video.addEventListener('progress', App.Timeline.updateBuffered);
            video.addEventListener('volumechange', () => {
                App.Player.updateVolumeUI();
                App.Settings.values.volume = video.volume;
                App.Settings.values.muted = video.muted;
                App.Settings.save();
            });
            video.addEventListener('ratechange', () => {
                El.speedLabel.textContent = Utils.formatRate(video.playbackRate);
                El.speedBtn.classList.toggle('active', video.playbackRate !== 1);
            });
            video.addEventListener('enterpictureinpicture', () => El.pipBtn.classList.add('active'));
            video.addEventListener('leavepictureinpicture', () => El.pipBtn.classList.remove('active'));

            document.addEventListener('fullscreenchange', () => {
                const on = !!document.fullscreenElement;
                document.body.classList.toggle('fullscreen', on);
                El.fsEnterIcon.style.display = on ? 'none' : 'block';
                El.fsExitIcon.style.display = on ? 'block' : 'none';
                App.Timeline.updateRect();
            });

            // Ambilight, throttled to 10 fps
            setInterval(() => {
                if (!video.paused && !video.ended && !document.hidden) App.Player.drawAmbilight();
            }, 100);

            // Click the video to play/pause, double-click for fullscreen
            El.videoClickSurface.addEventListener('click', () => {
                if (!State.source) return;
                clearTimeout(clickTimer);
                clickTimer = setTimeout(App.Player.togglePlay, 220);
            });
            El.videoClickSurface.addEventListener('dblclick', () => {
                clearTimeout(clickTimer);
                App.Player.toggleFullscreen();
            });
            El.audioVisualizer.addEventListener('click', App.Player.togglePlay);

            // Control bar
            El.playBtn.addEventListener('click', App.Player.togglePlay);
            El.stopBtn.addEventListener('click', App.Player.stop);
            El.muteBtn.addEventListener('click', App.Player.toggleMute);
            El.volumeSlider.addEventListener('input', () => App.Player.setVolume(parseFloat(El.volumeSlider.value)));
            El.volumeWrap.addEventListener('wheel', e => {
                e.preventDefault();
                App.Player.changeVolume(e.deltaY < 0 ? 0.05 : -0.05);
            }, { passive: false });
            El.loopBtn.addEventListener('click', App.Player.toggleLoop);
            El.speedWrap.addEventListener('wheel', e => {
                e.preventDefault();
                const step = e.shiftKey ? 0.25 : 0.05;
                App.Player.setSpeed(video.playbackRate + (e.deltaY < 0 ? step : -step));
            }, { passive: false });
            El.speedBtn.addEventListener('click', () => App.Player.setSpeed(1));
            El.openBtn.addEventListener('click', App.Player.openFileDialog);
            El.emptyOpenBtn.addEventListener('click', App.Player.openFileDialog);
            El.pipBtn.addEventListener('click', App.Player.togglePiP);
            El.settingsBtn.addEventListener('click', () => App.Dialogs.toggle('settingsModal'));
            El.fullscreenBtn.addEventListener('click', App.Player.toggleFullscreen);

            // File pickers
            El.fileInput.addEventListener('change', () => {
                if (El.fileInput.files.length > 0) App.Player.loadFile(El.fileInput.files[0]);
                El.fileInput.value = '';
            });
            El.subInput.addEventListener('change', () => {
                if (El.subInput.files.length > 0) App.Player.loadSubtitleFile(El.subInput.files[0]);
                El.subInput.value = '';
            });

            // Drag and drop (also stops WebView2 from navigating to the dropped file)
            window.addEventListener('dragover', e => {
                e.preventDefault();
                El.playerContainer.classList.add('drag-active');
            });
            window.addEventListener('dragleave', e => {
                if (!e.relatedTarget) El.playerContainer.classList.remove('drag-active');
            });
            window.addEventListener('drop', e => {
                e.preventDefault();
                El.playerContainer.classList.remove('drag-active');
                if (e.dataTransfer.files.length > 0) App.Player.loadFile(e.dataTransfer.files[0]);
            });

            window.addEventListener('beforeunload', App.Player.unload);
        }
    };
})();
