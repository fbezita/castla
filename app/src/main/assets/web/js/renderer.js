/**
 * Canvas Renderer
 * Renders VideoFrame objects to a canvas with proper aspect ratio
 */
class CanvasRenderer {
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.fitMode = 'contain';
        this.videoWidth = 0;
        this.videoHeight = 0;
        this.renderX = 0;
        this.renderY = 0;
        this.renderWidth = 0;
        this.renderHeight = 0;
        this.frameCount = 0;
        this.lastFpsTime = performance.now();
        this.currentFps = 0;
        
        // ### 수정 시작 ###
        // Obsoleted temporary stretch properties to prevent stretch afterimages.
        this.tempFillPending = false;
        this.tempFillTargetMode = null;
        this.tempFillTargetAspect = null;
        // ### 수정 끝 ###
    }

    enableTemporaryFill(targetMode = 'single', targetAspect = null) {
        // ### 수정 시작 ###
        // Completely disabled the temporary fill mechanism to support clean contain-based transition.
        // This ensures the video maintains its original aspect ratio inside the expanded canvas.
        // ### 수정 끝 ###
    }

    setFitMode(mode) {
        const nextMode = (mode === 'cover' || mode === 'fill') ? mode : 'contain';
        if (this.fitMode === nextMode) return;
        this.fitMode = nextMode;
        if (this.videoWidth > 0 && this.videoHeight > 0) {
            this.updateLayout();
        }
    }

    /**
     * Render a VideoFrame to the canvas
     * @param {VideoFrame} frame
     */
    render(frame) {
        const sourceWidth = frame.displayWidth || frame.width;
        const sourceHeight = frame.displayHeight || frame.height;

        // ### 수정 시작 ###
        // if (this.frameCount % 60 === 0) {
        //     console.log(`[RendererTelemetry] Frame #${this.frameCount}: size=${sourceWidth}x${sourceHeight}, canvasElement=${this.canvas.id}, clientSize=${this.canvas.clientWidth}x${this.canvas.clientHeight}, opacity=${this.canvas.style.opacity || 'default(0)'}, display=${this.canvas.style.display || 'default'}`);
        // }
        // ### 수정 끝 ###

        if (sourceWidth !== this.videoWidth || sourceHeight !== this.videoHeight) {
            this.videoWidth = sourceWidth;
            this.videoHeight = sourceHeight;
            
            // ### 수정 시작 ###
            // Invoke callback to notify the main thread of the frame resolution change instantly.
            if (typeof this.onFrameResolutionChange === 'function') {
                this.onFrameResolutionChange(sourceWidth, sourceHeight);
            }
            // ### 수정 끝 ###
            
            // New stream resolution received from the server — evaluate Aspect Ratio protection
            if (this.tempFillPending) {
                let shouldRelease = true;
                const aspect = sourceWidth / sourceHeight;
                
                // Get the physical aspect ratio of the canvas element dynamically
                const canvasWidth = this.canvas.clientWidth || window.innerWidth || 1;
                const canvasHeight = this.canvas.clientHeight || window.innerHeight || 1;
                let canvasAspect = canvasWidth / canvasHeight;

                // [CRITICAL TIMING SAFEGUARD] Prioritize explicit target aspect ratio to completely
                // bypass asynchronous browser reflow and layout synchronization delays.
                if (this.tempFillTargetAspect && Number.isFinite(this.tempFillTargetAspect) && this.tempFillTargetAspect > 0) {
                    canvasAspect = this.tempFillTargetAspect;
                }

                if (this.tempFillTargetMode === 'single') {
                    // Hold stretch mode (fill) until the incoming video stream aspect ratio settled close to the physical canvas aspect ratio
                    // Single fullscreen mode expects a landscape aspect ratio matching the fullscreen canvas.
                    if (aspect < canvasAspect - 0.08) {
                        console.log(`[Renderer] Aspect guard: received frame aspect ${aspect.toFixed(2)} while expecting fullscreen aspect >= ${(canvasAspect - 0.08).toFixed(2)} (${sourceWidth}x${sourceHeight}), holding fill mode.`);
                        shouldRelease = false;
                    }
                } else if (this.tempFillTargetMode === 'browser_split') {
                    // Hold stretch mode (fill) until the incoming video stream aspect ratio settled close to or narrower than the split canvas aspect ratio
                    if (aspect > canvasAspect + 0.08) {
                        console.log(`[Renderer] Aspect guard: received frame aspect ${aspect.toFixed(2)} while expecting split aspect <= ${(canvasAspect + 0.08).toFixed(2)} (${sourceWidth}x${sourceHeight}), holding fill mode.`);
                        shouldRelease = false;
                    }
                }

                if (shouldRelease) {
                    console.log(`[Renderer] Settled aspect/resolution ${sourceWidth}x${sourceHeight} (aspect: ${aspect.toFixed(2)}) received matching target aspect, clearing tempFillPending.`);
                    this.tempFillPending = false;
                    this.tempFillTargetMode = null;
                    this.tempFillTargetAspect = null;
                }
            }
            
            this.updateLayout();
        } else if (this.canvas.width !== this.canvas.clientWidth || this.canvas.height !== this.canvas.clientHeight) {
            this.updateLayout();
        }

        this.ctx.drawImage(frame, this.renderX, this.renderY, this.renderWidth, this.renderHeight);
        if (typeof frame.close === 'function') {
            frame.close();
        }

        this.frameCount++;
        this.updateFps();
    }

    updateLayout() {
        // Use the canvas element's own CSS size, not the parent container.
        // When the ad banner is visible, the canvas is shorter than the container
        // due to flex layout — using container size would cause touch Y offset.
        const canvasWidth = this.canvas.clientWidth;
        const canvasHeight = this.canvas.clientHeight;

        // ### 수정 시작 ###
        // Safely bypass layout synchronization when canvas is visually hidden
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            return;
        }
        // ### 수정 끝 ###

        this.canvas.width = canvasWidth;
        this.canvas.height = canvasHeight;

        // Calculate aspect-ratio-correct rendering area (letterbox/pillarbox)
        const videoAspect = this.videoWidth / this.videoHeight;
        const canvasAspect = canvasWidth / canvasHeight;

        // ### 수정 시작 ###
        // Fit mode is strictly preserved according to user config to prevent visual stretch distortion.
        const effectiveFitMode = this.fitMode;
        // ### 수정 끝 ###

        if (effectiveFitMode === 'fill') {
            this.renderWidth = canvasWidth;
            this.renderHeight = canvasHeight;
            this.renderX = 0;
            this.renderY = 0;
        } else if (effectiveFitMode === 'cover') {
            if (videoAspect > canvasAspect) {
                this.renderHeight = canvasHeight;
                this.renderWidth = canvasHeight * videoAspect;
                this.renderX = (canvasWidth - this.renderWidth) / 2;
                this.renderY = 0;
            } else {
                this.renderWidth = canvasWidth;
                this.renderHeight = canvasWidth / videoAspect;
                this.renderX = 0;
                this.renderY = (canvasHeight - this.renderHeight) / 2;
            }
        } else if (videoAspect > canvasAspect) {
            // Video is wider — letterbox (black bars top/bottom)
            this.renderWidth = canvasWidth;
            this.renderHeight = canvasWidth / videoAspect;
            this.renderX = 0;
            this.renderY = (canvasHeight - this.renderHeight) / 2;
        } else {
            // Video is taller — pillarbox (black bars left/right)
            this.renderHeight = canvasHeight;
            this.renderWidth = canvasHeight * videoAspect;
            this.renderX = (canvasWidth - this.renderWidth) / 2;
            this.renderY = 0;
        }

        // Clear canvas (for letterbox/pillarbox bars)
        this.ctx.fillStyle = '#000';
        this.ctx.fillRect(0, 0, canvasWidth, canvasHeight);
    }

    canvasToVideo(canvasX, canvasY) {
        // ### 수정 시작 ###
        // Apply a generous margin of 5% (0.05) to bounds checks to prevent touch event drops near edges
        const touchMargin = 0.05;
        if (!this.renderWidth || isNaN(this.renderWidth) || this.renderWidth <= 0 ||
            !this.renderHeight || isNaN(this.renderHeight) || this.renderHeight <= 0) {
            const canvasWidth = this.canvas.clientWidth || 1;
            const canvasHeight = this.canvas.clientHeight || 1;
            const x = canvasX / canvasWidth;
            const y = canvasY / canvasHeight;
            return {
                x: Math.max(0, Math.min(1, x)),
                y: Math.max(0, Math.min(1, y)),
                inBounds: x >= -touchMargin && x <= 1 + touchMargin && y >= -touchMargin && y <= 1 + touchMargin
            };
        }
        const x = (canvasX - this.renderX) / this.renderWidth;
        const y = (canvasY - this.renderY) / this.renderHeight;
        return {
            x: Math.max(0, Math.min(1, x)),
            y: Math.max(0, Math.min(1, y)),
            inBounds: x >= -touchMargin && x <= 1 + touchMargin && y >= -touchMargin && y <= 1 + touchMargin
        };
        // ### 수정 끝 ###
    }

    updateFps() {
        const now = performance.now();
        if (now - this.lastFpsTime >= 1000) {
            this.currentFps = this.frameCount;
            this.frameCount = 0;
            this.lastFpsTime = now;
        }
    }

    getFps() {
        return this.currentFps;
    }

    destroy() {
        this.ctx = null;
    }
}
