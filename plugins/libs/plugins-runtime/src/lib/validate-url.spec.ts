import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { getPenpotOrigin, validateUIUrl } from './validate-url.js';

describe('validate-url', () => {
  const originalLocation = globalThis.location;
  const originalPenpotPublicURI = (globalThis as any).penpotPublicURI;

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

  describe('validateUIUrl', () => {
    it('should throw when URL has same origin as location.origin', () => {
      const penpotOrigin = originalLocation.origin;
      expect(() => validateUIUrl(`${penpotOrigin}/some/path`)).toThrow(
        "Plugin UI URL must not point to Penpot's own domain",
      );
    });

    it('should not throw when URL has different origin', () => {
      expect(() =>
        validateUIUrl('https://example.com/plugin-ui'),
      ).not.toThrow();
    });

    it('should throw when URL matches penpotPublicURI origin', () => {
      (globalThis as any).penpotPublicURI = 'https://design.penpot.com/';
      expect(() =>
        validateUIUrl('https://design.penpot.com/some/path'),
      ).toThrow("Plugin UI URL must not point to Penpot's own domain");
    });

    it('should not throw when URL has same hostname but different port', () => {
      const url = new URL(originalLocation.origin);
      const differentPort = `${url.protocol}//${url.hostname}:9999`;
      expect(() => validateUIUrl(`${differentPort}/path`)).not.toThrow();
    });

    it('should throw even when URL has different path on same origin', () => {
      const penpotOrigin = originalLocation.origin;
      expect(() =>
        validateUIUrl(`${penpotOrigin}/deeply/nested/path`),
      ).toThrow();
    });
  });
});
