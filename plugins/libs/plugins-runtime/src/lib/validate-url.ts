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

/**
 * Whether the given URL is served from Penpot's own origin. Unparseable URLs
 * are considered external.
 */
export function isPenpotOrigin(url: string): boolean {
  try {
    return new URL(url).origin === getPenpotOrigin();
  } catch {
    return false;
  }
}

/**
 * Rejects UI URLs that resolve to Penpot's own origin, which would let the
 * plugin iframe escape its sandbox isolation.
 *
 * Plugins whose manifest is itself served from Penpot's origin are part of the
 * instance and are exempt from the check.
 */
export function validateUIUrl(url: string, manifestHost: string): void {
  if (isPenpotOrigin(manifestHost)) {
    return;
  }

  const parsed = new URL(url);
  if (parsed.origin === getPenpotOrigin()) {
    throw new Error(
      `Plugin UI URL must not point to Penpot's own domain: ${url}`,
    );
  }
}
