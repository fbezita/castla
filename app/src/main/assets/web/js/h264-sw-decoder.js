/**
 * Wasm-based H.264 Software Decoder Wrapper (Broadway Bridge)
 * Decodes High/Main/Baseline H.264 profiles using compiled Emscripten/Wasm Web Decoder.
 * Fallbacks cleanly to MJPEG if the browser/network fails to initialize the software core.
 */

class H264SwDecoder {
  constructor(onFrame, onError) {
    this.onFrame = onFrame;
    this.onError = onError;
    this.canvas = null;
    this.ctx = null;
    this.decoder = null;
    this.initialized = false;
    this.frameCount = 0;
    this.startTime = 0;

    // Decoupled stats tracking for quality engine integration
    this._droppedFrames = 0;
    this._renderedFrames = 0;
    this._totalDecodeLatency = 0;
    this._metricsStartTime = 0;

    // Cache parameters for initial keyframe sync
    this._cachedSpsPps = null;
    this._lastSeqNum = undefined;
  }

  /**
   * Evaluates if the environment is capable of running the Wasm Software H.264 engine.
   * Requires WebAssembly support which is ubiquitous in modern browsers.
   */
  static isSupported() {
    return typeof WebAssembly !== "undefined";
  }

  /**
   * Initializes the software decoder.
   * Loads the compiled Broadway Decoder dynamically from local assets or secure CDN fallbacks.
   */
  async init(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.startTime = performance.now();
    this._metricsStartTime = performance.now();

    try {
      await this._loadDecoderScript();
      
      if (typeof Decoder === "undefined") {
        throw new Error("Broadway H.264 Software Decoder module 'Decoder' not found in scope.");
      }

      // Initialize the underlying Emscripten/Wasm decoder instance
      // Using 'rgb: true' delegates highly optimized C++/Wasm conversion of YUV to RGBA pixels,
      // avoiding sluggish JS-side color space transformation loops.
      this.decoder = new Decoder({
        rgb: true
      });

      // Hook picture decoded callback
      this.decoder.onPictureDecoded = (buffer, width, height) => {
        const renderStart = performance.now();
        this._renderRgbaFrame(buffer, width, height);
        
        this.frameCount++;
        this._renderedFrames++;
        this._totalDecodeLatency += (performance.now() - renderStart);

        if (this.onFrame) {
          this.onFrame();
        }
      };

      this.initialized = true;
      console.log("[H264SwDecoder] WebAssembly software decoder successfully initialized.");
    } catch (e) {
      console.error("[H264SwDecoder] Failed to initialize software H.264 engine:", e);
      if (this.onError) {
        this.onError(e);
      }
      throw e; // Bubble up error to trigger triple fallback routing
    }
  }

  /**
   * Inject and load the required decoder runtime script.
   * Leverages robust local assets primary loading and reliable CDN failovers.
   */
  _loadDecoderScript() {
    return new Promise((resolve, reject) => {
      if (typeof Decoder !== "undefined") {
        return resolve();
      }

      const script = document.createElement("script");
      script.type = "text/javascript";
      
      // Secondary fallback paths for high availability in both offline vehicular routers and online devices
      const paths = [
        "js/broadway/Decoder.js",
        "https://cdn.jsdelivr.net/gh/mbebenita/Broadway/Player/Decoder.js"
      ];

      let pathIndex = 0;

      const loadNext = () => {
        if (pathIndex >= paths.length) {
          return reject(new Error("All paths to H.264 Broadway Decoder script failed to load."));
        }

        const currentPath = paths[pathIndex++];
        console.log(`[H264SwDecoder] Attempting to load runtime from: ${currentPath}`);
        
        script.src = currentPath;
        script.onload = () => {
          console.log(`[H264SwDecoder] Successfully loaded decoder runtime: ${currentPath}`);
          resolve();
        };
        script.onerror = () => {
          console.warn(`[H264SwDecoder] Failed to load decoder runtime from: ${currentPath}`);
          loadNext();
        };
      };

      document.head.appendChild(script);
      loadNext();
    });
  }

  /**
   * Decode an H.264 NAL Unit frame from WebSocket
   * Strips the 8-byte Castla stream header and feeds the raw frame into Broadway engine
   */
  decode(data) {
    if (!this.initialized || !this.decoder) return;

    const view = new DataView(data);
    if (data.byteLength < 9) return;

    const flags = view.getUint8(0);
    const seqNum = view.getUint16(1, true);

    // 0x02 = SPS/PPS config - cache config NALs to prepend to incoming keyframes
    if (flags === 0x02) {
      this._cachedSpsPps = data.slice(8);
      return;
    }

    const isKeyFrame = flags === 0x01;
    this._lastSeqNum = seqNum;

    const nalData = data.slice(8); // Strip 8-byte header
    let frameData = nalData;

    // Prepend cached SPS/PPS config to keyframes to ensure software decoder state consistency
    if (isKeyFrame && this._cachedSpsPps) {
      const spsPps = new Uint8Array(this._cachedSpsPps);
      const combined = new Uint8Array(spsPps.length + nalData.byteLength);
      combined.set(spsPps);
      combined.set(new Uint8Array(nalData), spsPps.length);
      frameData = combined.buffer;
    }

    try {
      // Feed binary NAL units into Broadway decoder (expects Uint8Array)
      const feedData = new Uint8Array(frameData);
      this.decoder.decode(feedData);
    } catch (e) {
      console.error("[H264SwDecoder] Decode runtime error:", e);
      if (this.onError) {
        this.onError(e);
      }
    }
  }

  /**
   * Renders the RGBA pixel array onto the 2D canvas context.
   */
  /* ### 수정 시작 ### */
  _renderRgbaFrame(rgbaBuffer, width, height) {
    if (!this.canvas || !this.ctx) return;

    try {
      // Validate buffer length to prevent OutOfBounds errors
      const expectedSize = width * height * 4;
      if (rgbaBuffer.byteLength < expectedSize) {
        console.warn(`[H264SwDecoder] Buffer size mismatch. Expected ${expectedSize} but got ${rgbaBuffer.byteLength}. Skipping frame.`);
        return;
      }

      // Dynamically align viewport size dimensions if mismatch detected
      if (this.canvas.width !== width || this.canvas.height !== height) {
        console.log(`[H264SwDecoder] Auto-fitting canvas dimensions: ${width}x${height}`);
        this.canvas.width = width;
        this.canvas.height = height;
      }

      // Convert raw RGBA Uint8ClampedArray directly to ImageData for immediate GPU drawing
      const imgData = new ImageData(new Uint8ClampedArray(rgbaBuffer.buffer, rgbaBuffer.byteOffset, expectedSize), width, height);
      this.ctx.putImageData(imgData, 0, 0);
    } catch (e) {
      console.error("[H264SwDecoder] Failed to render RGBA frame:", e);
    }
  }
  /* ### 수정 끝 ### */

  getFps() {
    const elapsed = (performance.now() - this.startTime) / 1000;
    if (elapsed < 1) return 0;
    return Math.round(this.frameCount / elapsed);
  }

  resetStats() {
    this.frameCount = 0;
    this.startTime = performance.now();
  }

  /**
   * Quality metric API for seamless backpressure monitoring.
   */
  getBacklogMetrics() {
    return {
      backlogHits: this._droppedFrames,
      backlogDrops: this._droppedFrames,
      decodeQueueSize: 0 // Software decoder handles queue synchronously
    };
  }

  /**
   * Performance metrics reporting API for real-time engine telemetry.
   */
  getMetrics() {
    const avgLatency = this._renderedFrames > 0
      ? parseFloat((this._totalDecodeLatency / this._renderedFrames).toFixed(1))
      : 0;
    return {
      profile: "wasm_software",
      droppedFrames: this._droppedFrames,
      renderedFrames: this._renderedFrames,
      bufferDepth: 0,
      avgRenderDelayMs: avgLatency,
      totalLatency: this._totalDecodeLatency
    };
  }

  resetMetrics() {
    this._droppedFrames = 0;
    this._renderedFrames = 0;
    this._totalDecodeLatency = 0;
    this._metricsStartTime = performance.now();
  }

  destroy() {
    this.initialized = false;
    this.decoder = null;
    this.canvas = null;
    this.ctx = null;
  }
}

// Bind to window scope for global ESM module accessibility
window.H264SwDecoder = H264SwDecoder;
