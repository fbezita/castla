export interface EmbeddedUiScaleInput {
  userAgent: string;
  viewportWidth: number;
  viewportHeight: number;
}

export interface EmbeddedUiScaleResult {
  isEmbeddedAutomotive: boolean;
  scale: number;
}

const EMBEDDED_AUTOMOTIVE_UA_PATTERNS = [
  /Tesla/i,
  /QtCarBrowser/i,
  /QtWebEngine/i,
];

function isEmbeddedAutomotiveUserAgent(userAgent: string): boolean {
  return EMBEDDED_AUTOMOTIVE_UA_PATTERNS.some((pattern) => pattern.test(userAgent));
}

export function resolveEmbeddedUiScale(input: EmbeddedUiScaleInput): EmbeddedUiScaleResult {
  const { userAgent, viewportWidth, viewportHeight } = input;
  const isEmbeddedAutomotive = isEmbeddedAutomotiveUserAgent(userAgent);

  if (!isEmbeddedAutomotive) {
    return {
      isEmbeddedAutomotive: false,
      scale: 1,
    };
  }

  const shortestSide = Math.min(viewportWidth, viewportHeight);
  const scale = shortestSide >= 720 ? 1.25 : 1.15;

  return {
    isEmbeddedAutomotive: true,
    scale,
  };
}
