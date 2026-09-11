import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  getPenpotOrigin,
  isPenpotOrigin,
  validateUIUrl,
} from './validate-url.js';

describe('validate-url', () => {
  const originalLocation = globalThis.location;
  const originalPenpotPublicURI = (globalThis as any).penpotPublicURI;
  const externalHost = 'https://example.com';

  beforeEach(() => {
    delete (globalThis as any).penpotPublicURI;
  });

  afterEach(() => {
    if (originalPenpotPublicURI !== undefined) {
      (globalThis as any).penpotPublicURI = originalPenpotPublicURI;
    } else {
      delete (globalThis as any).penpotPublicURI;
    }
  });

  describe('getPenpotOrigin', () => {
    it('should return location.origin when penpotPublicURI is not set', () => {
      expect(getPenpotOrigin()).toBe(originalLocation.origin);
    });

    it('should return origin from penpotPublicURI when set', () => {
      (globalThis as any).penpotPublicURI = 'https://design.penpot.com/';
      expect(getPenpotOrigin()).toBe('https://design.penpot.com');
    });

    it('should fall back to location.origin when penpotPublicURI is invalid', () => {
      (globalThis as any).penpotPublicURI = 'not-a-valid-url';
      expect(getPenpotOrigin()).toBe(originalLocation.origin);
    });
  });

  describe('isPenpotOrigin', () => {
    it('should be true for a URL on Penpot origin', () => {
      expect(
        isPenpotOrigin(`${originalLocation.origin}/plugin/manifest.json`),
      ).toBe(true);
    });

    it('should be false for a URL on another origin', () => {
      expect(isPenpotOrigin(`${externalHost}/manifest.json`)).toBe(false);
    });

    it('should be false for an unparseable URL', () => {
      expect(isPenpotOrigin('not-a-valid-url')).toBe(false);
    });
  });

  describe('validateUIUrl', () => {
    it('should throw when URL has same origin as location.origin', () => {
      const penpotOrigin = originalLocation.origin;
      expect(() =>
        validateUIUrl(`${penpotOrigin}/some/path`, externalHost),
      ).toThrow("Plugin UI URL must not point to Penpot's own domain");
    });

    it('should not throw when URL has different origin', () => {
      expect(() =>
        validateUIUrl('https://example.com/plugin-ui', externalHost),
      ).not.toThrow();
    });

    it('should throw when URL matches penpotPublicURI origin', () => {
      (globalThis as any).penpotPublicURI = 'https://design.penpot.com/';
      expect(() =>
        validateUIUrl('https://design.penpot.com/some/path', externalHost),
      ).toThrow("Plugin UI URL must not point to Penpot's own domain");
    });

    it('should not throw when URL has same hostname but different port', () => {
      const url = new URL(originalLocation.origin);
      const differentPort = `${url.protocol}//${url.hostname}:9999`;
      expect(() =>
        validateUIUrl(`${differentPort}/path`, externalHost),
      ).not.toThrow();
    });

    it('should throw even when URL has different path on same origin', () => {
      const penpotOrigin = originalLocation.origin;
      expect(() =>
        validateUIUrl(`${penpotOrigin}/deeply/nested/path`, externalHost),
      ).toThrow();
    });

    it('should not throw when the manifest is served from Penpot origin', () => {
      const penpotOrigin = originalLocation.origin;
      expect(() =>
        validateUIUrl(`${penpotOrigin}/some/path`, `${penpotOrigin}/plugin`),
      ).not.toThrow();
    });

    it('should not throw when the manifest is served from penpotPublicURI origin', () => {
      (globalThis as any).penpotPublicURI = 'https://design.penpot.com/';
      expect(() =>
        validateUIUrl(
          'https://design.penpot.com/some/path',
          'https://design.penpot.com/plugin',
        ),
      ).not.toThrow();
    });

    it('should still throw when the manifest host is unparseable', () => {
      const penpotOrigin = originalLocation.origin;
      expect(() => validateUIUrl(`${penpotOrigin}/path`, '')).toThrow(
        "Plugin UI URL must not point to Penpot's own domain",
      );
    });
  });
});
