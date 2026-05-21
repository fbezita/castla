/* ### 수정 시작 ### */
class MseDecoder {
    constructor(videoElementId = 'mse-video', onError) {
        // Handle optional single parameter case for backward compatibility
        let actualId = 'mse-video';
        let actualOnError = onError;
        if (typeof videoElementId === 'function') {
            actualOnError = videoElementId;
            actualId = 'mse-video';
        } else if (typeof videoElementId === 'string') {
            actualId = videoElementId;
        }

        this.videoElementId = actualId;
        this.video = document.getElementById(actualId);
        this.onError = actualOnError;
        this.jmuxer = null;
        this.ready = false;
        
        // Target latency profile for real-time mirroring
        this.targetLatency = 0.15; // 150ms target latency
        this.maxLatency = 0.40;    // 400ms maximum tolerable latency
        this.latencyTimer = null;
        this.framesDecoded = 0;
        this.lastDecodeTime = 0;

        /* ### 수정 시작 ### */
        // Cache standalone SPS/PPS config packets to prevent raw feeding buffer errors in JMuxer
        this._cachedSpsPps = null;
        this.playStarted = false;
        this.renderer = this; // Self-renderer binding for layout engine compatibility
        this._canvasBackupStyles = null; // Backup cache for overlay canvas style restoration
        /* ### 수정 끝 ### */
    }

    static isSupported() {
        return typeof MediaSource !== 'undefined' && typeof JMuxer !== 'undefined';
    }

    init() {
        return new Promise((resolve, reject) => {
            if (!this.video) {
                console.error(`[MSE] Video element #${this.videoElementId} not found in DOM`);
                reject(new Error('Video element not found'));
                return;
            }

            try {
                /* ### 수정 시작 ### */
                // Force HTML5 native properties to fulfill standard autoplay safety policy
                this.video.muted = true;
                this.video.playsInline = true;
                this.video.setAttribute('muted', '');
                this.video.setAttribute('playsinline', '');

                // Ensure absolute container layout to prevent collapsing under flex flow
                this.video.style.position = 'absolute';
                this.video.style.top = '0';
                this.video.style.left = '0';
                this.video.style.width = '100%';
                this.video.style.height = '100%';
                this.video.style.objectFit = 'contain';
                this.video.style.backgroundColor = '#000';
                
                // Show video element natively beneath the transparent touch canvas
                this.video.style.display = 'block';
                this.video.style.opacity = '1'; // Ensure video is fully visible to prevent blackout issues
                this.video.style.zIndex = '1';
                
                const canvasId = this.videoElementId === 'mse-video-secondary' ? 'display-secondary' : 'display';
                const canvas = document.getElementById(canvasId);
                if (canvas) {
                    // Backup original canvas styles so we can faithfully restore them on destroy()
                    this._canvasBackupStyles = {
                        display: canvas.style.display,
                        position: canvas.style.position,
                        top: canvas.style.top,
                        left: canvas.style.left,
                        width: canvas.style.width,
                        height: canvas.style.height,
                        opacity: canvas.style.opacity,
                        zIndex: canvas.style.zIndex
                    };
                    
                    // Transform the canvas into a transparent, zero-overhead Touch Interceptor Layer overlays perfectly on top of the <video>
                    canvas.style.display = 'block';
                    canvas.style.position = 'absolute';
                    canvas.style.top = '0';
                    canvas.style.left = '0';
                    canvas.style.width = '100%';
                    canvas.style.height = '100%';
                    canvas.style.opacity = '0';
                    canvas.style.zIndex = '10'; // Overlaid on top of the video
                }

                this.ready = true;
                console.log(`[MSE] Pre-initialization finished for #${this.videoElementId}. Awaiting H.264 stream to start JMuxer.`);
                resolve();
                /* ### 수정 끝 ### */
            } catch (e) {
                console.error('[MSE] Failed to pre-initialize MseDecoder:', e);
                reject(e);
            }
        });
    }

    /* ### 수정 시작 ### */
    // Helper method to parse profile_idc, constraint_set_flags, and level_idc directly from H.264 SPS NAL unit
    detectCodecFromSps(nalData) {
        for (let i = 0; i < nalData.length - 4; i++) {
            let startCodeLen = 0;
            if (nalData[i] === 0 && nalData[i + 1] === 0 && nalData[i + 2] === 1) {
                startCodeLen = 3;
            } else if (nalData[i] === 0 && nalData[i + 1] === 0 && nalData[i + 2] === 0 && nalData[i + 3] === 1) {
                startCodeLen = 4;
            }
            if (startCodeLen > 0) {
                const nalType = nalData[i + startCodeLen] & 0x1f;
                if (nalType === 7 && i + startCodeLen + 3 < nalData.length) {
                    const profile = nalData[i + startCodeLen + 1];
                    const compat = nalData[i + startCodeLen + 2];
                    const level = nalData[i + startCodeLen + 3];
                    return 'avc1.' + 
                        profile.toString(16).padStart(2, '0') + 
                        compat.toString(16).padStart(2, '0') + 
                        level.toString(16).padStart(2, '0');
                }
            }
        }
        return null;
    }

    // Lazy initialization of JMuxer once H.264 stream configuration is parsed
    ensureJmuxerInitialized(codecStr = null) {
        if (this.jmuxer) return;
        
        console.log(`[MSE] Initializing JMuxer lazily on #${this.videoElementId}. Detected H.264 Codec: ${codecStr || 'avc1.64001f'}`);
        
        try {
            this.jmuxer = new JMuxer({
                node: this.video,
                mode: 'video',
                flushingTime: 0,   // Ultra-low latency mode, feed chunks immediately to source buffer
                clearBuffer: true, // Automatically manage source buffer eviction to prevent QuotaExceededError
                fps: 60,           // Assume 60fps mirroring stream
                debug: false,
                onError: (err) => {
                    console.error('[MSE] JMuxer error:', err);
                    if (this.onError) this.onError(err);
                }
            });
            this.startLatencyController();
        } catch (e) {
            console.error('[MSE] Failed to initialize JMuxer lazily:', e);
            if (this.onError) this.onError(e);
        }
    }

    decode(data) {
        if (!this.ready || !data || data.byteLength < 8) return;

        // Extract Android MediaCodec protocol metadata header (8 bytes)
        const view = new DataView(data);
        const flags = view.getUint8(0); // 0x01: Keyframe, 0x02: SPS/PPS, 0x00: Delta frame
        
        // 0x02 = SPS/PPS config - cache and wait for keyframe, do not feed standalone to JMuxer
        if (flags === 0x02) {
            this._cachedSpsPps = data.slice(8);
            const rawSps = new Uint8Array(this._cachedSpsPps);
            const codec = this.detectCodecFromSps(rawSps);
            if (codec) {
                console.log(`[MSE] Dynamically parsed SPS H.264 codec: ${codec}`);
            }
            console.log(`[MSE] Intercepted H.264 SPS/PPS config packet. Size: ${this._cachedSpsPps.byteLength}`);
            return;
        }

        const isKeyFrame = flags === 0x01;
        const nalData = data.slice(8);

        // Prepend cached SPS/PPS to keyframes to ensure JMuxer initializes MSE source buffer correctly
        let payload = new Uint8Array(nalData);
        if (isKeyFrame && this._cachedSpsPps) {
            const spsPps = new Uint8Array(this._cachedSpsPps);
            const combined = new Uint8Array(spsPps.length + nalData.byteLength);
            combined.set(spsPps);
            combined.set(new Uint8Array(nalData), spsPps.length);
            payload = combined;
            console.log(`[MSE] Prepended cached SPS/PPS (${spsPps.length} bytes) to keyframe`);
        }

        // Initialize JMuxer lazily on the first playable keyframe/delta packet arrival
        if (!this.jmuxer) {
            const codecStr = this._cachedSpsPps ? this.detectCodecFromSps(new Uint8Array(this._cachedSpsPps)) : null;
            this.ensureJmuxerInitialized(codecStr);
        }

        if (this.jmuxer) {
            // Feed raw H.264 payload directly to JMuxer
            this.jmuxer.feed({
                video: payload
            });
        }

        this.framesDecoded++;
        this.lastDecodeTime = performance.now();

        // Keep retrying play() if video is still paused, to bypass transient loading AbortErrors
        if (this.video && this.video.paused && this.framesDecoded > 3) {
            this.play();
        }
        /* ### 수정 끝 ### */
    }

    startLatencyController() {
        // High-frequency polling (100ms) to ensure absolute real-time touch alignment
        this.latencyTimer = setInterval(() => {
            if (!this.video || this.video.buffered.length === 0) return;

            const bufferedEnd = this.video.buffered.end(this.video.buffered.length - 1);
            const currentTime = this.video.currentTime;
            const latency = bufferedEnd - currentTime;

            if (latency > this.maxLatency) {
                // High latency drift (Force immediate skip jump to live edge)
                this.video.currentTime = Math.max(0, bufferedEnd - 0.05);
            } else if (latency > this.targetLatency) {
                // Mild latency drift (Temporarily speed up playback rate to absorb buffer)
                this.video.playbackRate = 1.35;
            } else {
                // Healthy latency range (Resume standard speed)
                this.video.playbackRate = 1.0;
            }
        }, 100);
    }

    play() {
        /* ### 수정 시작 ### */
        if (this.video && this.video.paused) {
            this.video.play()
                .then(() => {
                    this.playStarted = true;
                })
                .catch(e => {
                    // Silence benign browser AbortError/NotAllowedError from interrupt play calls
                    if (e.name !== 'AbortError' && e.name !== 'NotAllowedError') {
                        console.error('[MSE] Playback start failed:', e);
                    }
                });
        }
        /* ### 수정 끝 ### */
    }

    /* ### 수정 시작 ### */
    setFitMode(fitMode) {
        if (!this.video) return;
        if (fitMode === 'fill') {
            this.video.style.objectFit = 'fill';
        } else if (fitMode === 'cover') {
            this.video.style.objectFit = 'cover';
        } else {
            this.video.style.objectFit = 'contain';
        }
        console.log(`[MSE] Dynamic fitMode updated on #${this.videoElementId}: ${fitMode}`);
    }

    // Projects coordinates from the transparent overlay canvas onto the actual active video boundaries
    // resolving any letterboxing or aspect-ratio padding automatically.
    canvasToVideo(x, y) {
        if (!this.video) return { x: 0, y: 0, inBounds: false };
        
        const rect = this.video.getBoundingClientRect();
        const touchMargin = 0.05; // 5% border margin buffer to prevent edge touch drops
        const fitMode = this.video.style.objectFit || "contain";
        
        if (fitMode === "fill") {
            // Direct 1:1 ratio mapping since the video is stretched to perfectly fit the viewport
            const normX = x / rect.width;
            const normY = y / rect.height;
            return {
                x: Math.max(0, Math.min(1, normX)),
                y: Math.max(0, Math.min(1, normY)),
                inBounds:
                    normX >= -touchMargin &&
                    normX <= 1 + touchMargin &&
                    normY >= -touchMargin &&
                    normY <= 1 + touchMargin
            };
        }
        
        // contain aspect ratio letterbox compensation logic
        const videoWidth = this.video.videoWidth;
        const videoHeight = this.video.videoHeight;
        
        if (videoWidth <= 0 || videoHeight <= 0) {
            // Fallback to simple direct mapping if video metadata is not yet parsed
            const normX = x / rect.width;
            const normY = y / rect.height;
            return {
                x: Math.max(0, Math.min(1, normX)),
                y: Math.max(0, Math.min(1, normY)),
                inBounds: true
            };
        }
        
        const videoAspect = videoWidth / videoHeight;
        const elemAspect = rect.width / rect.height;
        let renderX = 0, renderY = 0, renderW = rect.width, renderH = rect.height;
        
        if (videoAspect > elemAspect) {
            // Black bars top/bottom (letterbox)
            renderW = rect.width;
            renderH = rect.width / videoAspect;
            renderY = (rect.height - renderH) / 2;
        } else {
            // Black bars left/right (pillarbox)
            renderH = rect.height;
            renderW = rect.height * videoAspect;
            renderX = (rect.width - renderW) / 2;
        }
        
        const normX = (x - renderX) / renderW;
        const normY = (y - renderY) / renderH;
        
        return {
            x: Math.max(0, Math.min(1, normX)),
            y: Math.max(0, Math.min(1, normY)),
            inBounds:
                normX >= -touchMargin &&
                normX <= 1 + touchMargin &&
                normY >= -touchMargin &&
                normY <= 1 + touchMargin
        };
    }
    /* ### 수정 끝 ### */

    destroy() {
        this.ready = false;
        /* ### 수정 시작 ### */
        this._cachedSpsPps = null;
        this.playStarted = false;
        /* ### 수정 끝 ### */

        if (this.latencyTimer) {
            clearInterval(this.latencyTimer);
            this.latencyTimer = null;
        }

        if (this.jmuxer) {
            try {
                this.jmuxer.destroy();
            } catch (e) {
                console.error('[MSE] Error during JMuxer destruction:', e);
            }
            this.jmuxer = null;
        }

        // Hide video element natively
        if (this.video) {
            this.video.style.display = 'none';
        }
        
        // Restore transparent overlay canvas back to its original standard state
        const canvasId = this.videoElementId === 'mse-video-secondary' ? 'display-secondary' : 'display';
        const canvas = document.getElementById(canvasId);
        if (canvas && this._canvasBackupStyles) {
            const backup = this._canvasBackupStyles;
            canvas.style.display = backup.display || 'block';
            canvas.style.position = backup.position || '';
            canvas.style.top = backup.top || '';
            canvas.style.left = backup.left || '';
            canvas.style.width = backup.width || '';
            canvas.style.height = backup.height || '';
            canvas.style.opacity = backup.opacity || '1';
            canvas.style.zIndex = backup.zIndex || '';
            this._canvasBackupStyles = null;
        } else if (canvas) {
            // Hard fallback if backup is missing
            canvas.style.display = 'block';
            canvas.style.opacity = '1';
            canvas.style.zIndex = '';
        }

        this.framesDecoded = 0;
    }
}
/* ### 수정 끝 ### */
