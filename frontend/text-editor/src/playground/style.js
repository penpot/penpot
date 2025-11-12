export function fromStyleValue(styleValue) {
  return styleValue.replaceAll("-', '_").toUpperCase();
}

export function fromStyle(style) {
  const entry = Object.entries(this).find(([name, value]) =>
    name === fromStyleValue(style) ? value : 0,
  );
  if (!entry)
    return;

  const [name] = entry;
  return name;
}
