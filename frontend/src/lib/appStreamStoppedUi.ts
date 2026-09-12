export interface AppStreamStoppedUiAction {
  openDrawer: true;
  showHome: boolean;
  pane: string;
}

export function resolveAppStreamStoppedUi(
  message: unknown,
  hasSecondaryApp: boolean,
): AppStreamStoppedUiAction | null {
  if (typeof message !== "object" || message === null) return null;

  const controlMessage = message as { type?: unknown; pane?: unknown };
  if (controlMessage.type !== "APP_STREAM_STOPPED") return null;

  const pane = typeof controlMessage.pane === "string" ? controlMessage.pane : "primary";
  return {
    openDrawer: true,
    showHome: pane === "primary" && !hasSecondaryApp,
    pane,
  };
}
