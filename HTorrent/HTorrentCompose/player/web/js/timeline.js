// Seek bar: dragging, the hover tooltip with preview frames, the buffered range and stacked arrow-key seeks.
(function () {
    'use strict';

    const App = window.App;
    const { El, State, Utils } = App;
    const video = El.video;
    const thumbVideo = El.thumbVideo;
    const THUMB_SIZE = 12; // the slider thumb; its centre runs from half a thumb in on each side

    let hoverPending = false;
    let hoverX = 0;
    let thumbsBroken = false;
    let pendingThumbTime = null;

    function duration() {
        return Number.isFinite(video.duration) ? video.duration : 0;
    }

    function seekThumb(time) {
        if (typeof thumbVideo.fastSeek === 'function') thumbVideo.fastSeek(time);
        else thumbVideo.currentTime = time;
    }

    App.Timeline = {
        init() {
            window.addEventListener('resize', App.Timeline.updateRect, { passive: true });
            El.progressWrapper.addEventListener('mouseenter', App.Timeline.updateRect, { passive: true });

            // Press and release seeks once; moving more than a few pixels while pressed scrubs.
            El.progressWrapper.addEventListener('mousedown', e => {
                if (e.button !== 0 || !State.source || !duration()) return;
                e.preventDefault();
                App.Timeline.updateRect();
                Object.assign(State.scrub, { down: true, dragging: false, startX: e.clientX, startY: e.clientY });
            });

            window.addEventListener('mousemove', e => {
                App.OSD.resetUIHider(e);
                const scrub = State.scrub;
                if (!scrub.down) return;
                if (!scrub.dragging && (Math.abs(e.clientX - scrub.startX) > 4 || Math.abs(e.clientY - scrub.startY) > 4)) {
                    scrub.dragging = true;
                    El.progressWrapper.classList.add('scrubbing');
                }
                if (scrub.dragging) {
                    App.Timeline.scrubTo(App.Timeline.timeAt(e.clientX));
                    App.Timeline.showTooltip(e.clientX);
                }
            }, { passive: true });

            window.addEventListener('mouseup', e => {
                const scrub = State.scrub;
                if (!scrub.down) return;
                scrub.down = false;
                scrub.dragging = false;
                El.progressWrapper.classList.remove('scrubbing');
                if (!El.progressWrapper.matches(':hover')) El.timelineTooltip.classList.remove('show');
                App.Timeline.seekTo(App.Timeline.timeAt(e.clientX));
            });

            El.progressWrapper.addEventListener('mousemove', e => {
                hoverX = e.clientX;
                if (hoverPending) return;
                hoverPending = true;
                requestAnimationFrame(() => {
                    hoverPending = false;
                    App.Timeline.showTooltip(hoverX);
                });
            }, { passive: true });

            El.progressWrapper.addEventListener('mouseleave', () => {
                if (!State.scrub.dragging) El.timelineTooltip.classList.remove('show');
            });

            // Preview frames come from a second, muted copy of the video.
            thumbVideo.addEventListener('seeked', () => {
                if (pendingThumbTime !== null) {
                    const next = pendingThumbTime;
                    pendingThumbTime = null;
                    seekThumb(next);
                    return;
                }
                if (thumbVideo.videoWidth > 0) El.thumbContainer.classList.add('has-frame');
            });
            thumbVideo.addEventListener('loadedmetadata', () => {
                if (pendingThumbTime !== null) {
                    const next = pendingThumbTime;
                    pendingThumbTime = null;
                    seekThumb(next);
                }
            });
            thumbVideo.addEventListener('error', () => {
                if (!thumbVideo.getAttribute('src')) return;
                thumbsBroken = true;
                El.thumbContainer.classList.remove('has-frame');
            });
        },

        updateRect() {
            State.progressRect = El.progressWrapper.getBoundingClientRect();
        },

        timeAt(clientX) {
            const rect = State.progressRect || El.progressWrapper.getBoundingClientRect();
            const track = rect.width - THUMB_SIZE;
            const pos = track > 0 ? Utils.clamp((clientX - rect.left - THUMB_SIZE / 2) / track, 0, 1) : 0;
            return pos * duration();
        },

        showPosition(time) {
            const d = duration();
            El.progressBar.value = time;
            El.progressBar.style.setProperty('--progress-pct', `${d ? Utils.clamp(time / d * 100, 0, 100) : 0}%`);
            El.currentTime.textContent = Utils.formatTime(time);
        },

        // While dragging, local files follow the mouse. Torrent streams only seek on release, because every seek
        // makes HTorrent re-prioritize pieces for that position.
        scrubTo(time) {
            App.Timeline.showPosition(time);
            if (!App.Player.isLocal()) return;
            const now = performance.now();
            if (now - State.scrub.lastSeek < 60) return;
            State.scrub.lastSeek = now;
            if (typeof video.fastSeek === 'function') video.fastSeek(time);
            else video.currentTime = time;
        },

        seekTo(time) {
            const d = duration();
            if (!State.source || !d) return;
            time = Utils.clamp(time, 0, d);
            App.Timeline.showPosition(time);
            video.currentTime = time;
        },

        // Arrow keys add up: pressing Right three times seeks +15s once, with the arrows overlay showing the total.
        accumulateSeek(delta) {
            const d = duration();
            if (!State.source || !d) return;
            const acc = State.accum;
            if (!acc.active) {
                acc.active = true;
                acc.base = video.currentTime;
                acc.offset = 0;
            } else if (acc.offset !== 0 && Math.sign(delta) !== Math.sign(acc.offset)) {
                acc.base = Utils.clamp(acc.base + acc.offset, 0, d);
                acc.offset = 0;
            }

            const target = Utils.clamp(acc.base + acc.offset + delta, 0, d);
            acc.offset = target - acc.base;
            App.Timeline.showPosition(target);
            if (acc.offset !== 0) App.OSD.showSeekOverlay(Math.sign(acc.offset), acc.offset);
            else App.OSD.hideSeekOverlay();

            clearTimeout(acc.timer);
            acc.timer = setTimeout(() => {
                acc.active = false;
                App.OSD.hideSeekOverlay();
                App.Timeline.seekTo(target);
                App.OSD.resetUIHider();
            }, 450);
        },

        onTimeUpdate() {
            if (!State.scrub.down && !State.accum.active) App.Timeline.showPosition(video.currentTime);
            App.Timeline.updateBuffered();
        },

        onDurationChanged() {
            const d = duration();
            El.progressBar.max = d || 100;
            El.duration.textContent = Utils.formatTime(d);
            App.Timeline.showPosition(video.currentTime);
            App.Timeline.updateBuffered();
        },

        // How much has arrived after the playing position; for torrents this is the part HTorrent has sent.
        updateBuffered() {
            const d = duration();
            let end = 0;
            for (let i = 0; i < video.buffered.length; i++) {
                if (video.buffered.start(i) <= video.currentTime + 0.5) end = Math.max(end, video.buffered.end(i));
            }
            El.progressBar.style.setProperty('--buffered-pct', `${d ? Math.min(100, end / d * 100) : 0}%`);
        },

        showTooltip(clientX) {
            if (!State.source || !duration()) return;
            const time = App.Timeline.timeAt(clientX);
            const rect = State.progressRect || El.progressWrapper.getBoundingClientRect();
            El.thumbText.textContent = Utils.formatTime(time);
            El.timelineTooltip.classList.add('show');
            El.timelineTooltip.style.bottom = `${window.innerHeight - rect.top + 14}px`;
            const half = El.timelineTooltip.offsetWidth / 2;
            El.timelineTooltip.style.left = `${Utils.clamp(clientX, half + 8, window.innerWidth - half - 8)}px`;
            App.Timeline.requestThumbnail(time);
        },

        // Preview frames only for local files: on a torrent stream every hover would request (and prioritize) new pieces.
        requestThumbnail(time) {
            if (!App.Player.isLocal() || State.isAudio || thumbsBroken) {
                El.thumbContainer.classList.remove('has-frame');
                return;
            }
            if (thumbVideo.readyState >= 1 && !thumbVideo.seeking) seekThumb(time);
            else pendingThumbTime = time;
        },

        onSourceChanged() {
            thumbsBroken = false;
            pendingThumbTime = null;
            El.thumbContainer.classList.remove('has-frame');
            El.timelineTooltip.classList.remove('show');
            if (App.Player.isLocal()) {
                thumbVideo.src = State.source.url;
            } else if (thumbVideo.getAttribute('src')) {
                thumbVideo.removeAttribute('src');
                thumbVideo.load();
            }
            El.progressBar.max = 100;
            El.duration.textContent = '00:00';
            App.Timeline.showPosition(0);
            El.progressBar.style.setProperty('--buffered-pct', '0%');
        }
    };
})();
