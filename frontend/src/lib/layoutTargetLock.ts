export interface LayoutTargetLockInput {
  resizingSplit: boolean;
  popupInteracting: boolean;
  layoutTransitionActive: boolean;
  frozenLayoutActive: boolean;
}

export function shouldLockExplicitLayoutTargets({
  resizingSplit,
  popupInteracting,
  layoutTransitionActive,
  frozenLayoutActive,
}: LayoutTargetLockInput): boolean {
  if (resizingSplit || popupInteracting) {
    return false;
  }
  return layoutTransitionActive || frozenLayoutActive;
}
