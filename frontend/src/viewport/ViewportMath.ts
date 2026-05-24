export interface Size {
  width: number;
  height: number;
}

export function fitContain(container: Size, content: Size): Size {
  const scale = Math.min(container.width / content.width, container.height / content.height);
  return {
    width: Math.round(content.width * scale),
    height: Math.round(content.height * scale)
  };
}
