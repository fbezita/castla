# 모던 Wasm H.264 소프트웨어 디코더 교체 연동 가이드 (비상 대비책)

본 문서는 하드웨어 인코더 튜닝을 거쳤음에도 불구하고 일부 파편화가 극심한 제조사 단말기 드라이버 결함으로 인해 최종적으로 CABAC 비활성화가 실패하여 프론트엔드로 Main/High 수준의 H.264 스트림이 계속 방출될 때를 대비한 **최종 소프트웨어 디코더 코어 교체(Fallback) 기술 명세**입니다.

현재의 Broadway 디코더를 메인 및 하이 프로파일(CABAC)을 완벽 지원하는 모던 Wasm 기반 H.264 소프트웨어 디코더로 대체하는 기술적 방법과 아키텍처 설계를 규정합니다.

---

## 1. 대체 후보 소프트웨어 디코더 분석

### A. tinyh264 Wasm 포팅 버전
- **개요**: Google Android 소스 트리 내의 `tinyh264` 라이브러리를 Emscripten을 통해 WebAssembly로 경량화하여 컴파일한 Wasm 모듈.
- **장점**:
  - Main 및 High Profile(CABAC) 디코딩을 온전히 지원함.
  - Broadway와 비교하여 메모리 점유율 및 코드 풋프린트가 매우 작고 경량화됨.
- **적용 난이도**: 보통 (YUV -> RGBA 변환 로직이 내장되어 있어 Broadway와 인터페이스 호환성이 높음).

### B. libopenh264.wasm (Cisco OpenH264)
- **개요**: Cisco가 제공하는 OpenH264 소스코드를 WebAssembly 타겟으로 빌드하여 브라우저에서 로딩할 수 있게 구성한 Wasm 모듈.
- **장점**:
  - High Profile을 완벽 지원하며, 압도적인 디코딩 안정성과 표준 규격 준수율을 가짐.
  - 다중 스레드 디코딩(Wasm Threading) 적용 시 성능 효율 극대화.
- **적용 난이도**: 약간 높음 (YUV420p 데이터를 출력하므로 WebGL 또는 2D Canvas 드로잉을 위한 YUV-to-RGB 변환 셰이더 혹은 픽셀 맵핑 연산 추가 필요).

---

## 2. 프론트엔드 연동 아키텍처 및 인터페이스 설계

새로운 디코더 모듈은 기존 `H264SwDecoder` 클래스의 Public Interface 규격을 100% 동일하게 충족하도록 래핑 설계하여 프론트엔드 파이프라인의 수정을 최소화합니다.

```javascript
/**
 * Modern Wasm-based H.264 Software Decoder Wrapper (Replacement Candidate)
 * Supports CABAC, B-frames, Main, and High H.264 Profiles seamlessly.
 */
class ModernH264SwDecoder {
  constructor(onFrame, onError) {
    this.onFrame = onFrame;
    this.onError = onError;
    this.canvas = null;
    this.ctx = null;
    this.decoderModule = null;
    this.initialized = false;
  }

  static isSupported() {
    return typeof WebAssembly !== "undefined";
  }

  async init(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    
    try {
      // 1. Wasm 모듈 및 JS 글루 코드 로드
      await this._loadModernDecoderScript();
      
      // 2. 모던 Wasm 디코더 코어 인스턴스화
      // YUV to RGBA 변환을 C++/Wasm 영역에서 처리하도록 구성
      this.decoderModule = await createModernDecoderInstance({
        onPictureDecoded: (rgbaBuffer, width, height) => {
          this._renderRgbaFrame(rgbaBuffer, width, height);
          if (this.onFrame) this.onFrame();
        }
      });
      
      this.initialized = true;
      console.log("[ModernDecoder] Modern Wasm decoder initialized successfully.");
    } catch (e) {
      console.error("[ModernDecoder] Failed to init modern WASM core:", e);
      if (this.onError) this.onError(e);
      throw e;
    }
  }

  decode(data) {
    if (!this.initialized || !this.decoderModule) return;
    
    // 1. Castla 8바이트 커스텀 스트림 헤더 스트립
    const nalData = data.slice(8);
    
    // 2. Wasm 디코더 코어의 메모리 힙으로 스트림 데이터 주입 및 디코딩 수행
    const feedData = new Uint8Array(nalData);
    this.decoderModule.decode(feedData);
  }

  _renderRgbaFrame(rgbaBuffer, width, height) {
    if (!this.canvas || !this.ctx) return;
    
    // 캔버스 크기 동적 조율
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
    }
    
    // 2D ImageData put을 통한 고속 드로잉
    const imgData = new ImageData(
      new Uint8ClampedArray(rgbaBuffer.buffer, rgbaBuffer.byteOffset, width * height * 4),
      width,
      height
    );
    this.ctx.putImageData(imgData, 0, 0);
  }

  destroy() {
    this.initialized = false;
    if (this.decoderModule) {
      this.decoderModule.free();
      this.decoderModule = null;
    }
    this.canvas = null;
    this.ctx = null;
  }
}
```

---

## 3. 리소스 자산 배치 전략
- **Wasm 파일 배치 경로**: `app/src/main/assets/web/js/broadway/` 또는 별도 하위 `app/src/main/assets/web/js/modern-wasm/` 디렉토리를 생성하여 배포합니다.
- **로딩 경로 제어**: 
  - `_loadModernDecoderScript()` 호출 시 모바일 내부 오프라인 환경을 위해 로컬 자산 파일(`js/modern-wasm/modern_decoder.js`)을 최우선 탑재하도록 Primary 패스를 매핑하고, CDN Failover 패스를 차선책으로 지정합니다.
