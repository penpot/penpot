export function getPenpotOrigin(): string {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const publicUri = (globalThis as any).penpotPublicURI;
  if (publicUri) {
    try {
      return new URL(publicUri).origin;
    } catch {
      // fall through to location.origin
    }
  }
  return globalThis.location.origin;
}

export function validateUIUrl(url: string): void {
  const penpotOrigin = getPenpotOrigin();
  const parsed = new URL(url);
  if (parsed.origin === penpotOrigin) {
    throw new Error(
      `Plugin UI URL must not point to Penpot's own domain: ${url}`,
    );
  }
}
