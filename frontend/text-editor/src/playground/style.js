export function fromStyleValue(styleValue) {
  return styleValue.replaceAll("-', '_").toUpperCase();
}

export function fromStyle(style) {
  return Object.entries(this).find(([name, value]) =>
    name === fromStyleValue(style) ? value : 0,
  );
}
