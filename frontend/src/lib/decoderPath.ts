export function isJmuxerFrontendPath(): boolean {
  const decoderParam = new URLSearchParams(window.location.search).get("decoder");
  const forcesJmuxer = decoderParam === "jmuxer" || decoderParam === "mse";
  return forcesJmuxer || !window.isSecureContext || !("VideoDecoder" in window);
}
