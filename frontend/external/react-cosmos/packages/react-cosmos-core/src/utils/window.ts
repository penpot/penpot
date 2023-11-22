export function isInsideWindowIframe() {
  try {
    return window.self !== window.parent;
  } catch (e) {
    return true;
  }
}
