import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

// Plugin data and local storage.

describe('Plugin data', () => {
  test('plugin data round-trips on a shape', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setPluginData('exampleKey', 'exampleValue');
    expect(rect.getPluginData('exampleKey')).toBe('exampleValue');
    expect(rect.getPluginDataKeys()).toContain('exampleKey');
  });

  test('shared plugin data round-trips on a shape', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setSharedPluginData('ns', 'sharedKey', 'sharedValue');
    expect(rect.getSharedPluginData('ns', 'sharedKey')).toBe('sharedValue');
    expect(rect.getSharedPluginDataKeys('ns')).toContain('sharedKey');
  });

  test('plugin data round-trips on the file', (ctx) => {
    const file = ctx.penpot.currentFile;
    expect(file).not.toBeNull();
    if (file) {
      file.setPluginData('fileKey', 'fileValue');
      expect(file.getPluginData('fileKey')).toBe('fileValue');
    }
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests assert invalid keys/values are
  // rejected; "success" tests cover multi-key listing, overwrite, large values,
  // missing keys and local/shared isolation.
  // ---------------------------------------------------------------------------
  test('setPluginData with an empty key is accepted (currently unvalidated)', (ctx) => {
    // An empty key is not rejected; this pins the current lenient behaviour
    // (a candidate for future hardening): the value is stored and retrievable
    // under the empty key like any other.
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(() => rect.setPluginData('', 'value')).not.toThrow();
    expect(rect.getPluginData('')).toBe('value');
    expect(rect.getPluginDataKeys()).toContain('');
  });

  test('setPluginData with a non-string value throws', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(() => rect.setPluginData('key', 123 as unknown as string)).toThrow();
  });

  test('multiple keys round-trip and are all listed', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setPluginData('a', '1');
    rect.setPluginData('b', '2');
    rect.setPluginData('c', '3');
    expect(rect.getPluginData('a')).toBe('1');
    expect(rect.getPluginData('b')).toBe('2');
    expect(rect.getPluginData('c')).toBe('3');
    const keys = rect.getPluginDataKeys();
    expect(keys).toContain('a');
    expect(keys).toContain('b');
    expect(keys).toContain('c');
  });

  test('overwriting a key replaces its value', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setPluginData('k', 'first');
    rect.setPluginData('k', 'second');
    expect(rect.getPluginData('k')).toBe('second');
  });

  test('a large value round-trips', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    const big = 'x'.repeat(10000);
    rect.setPluginData('big', big);
    expect(rect.getPluginData('big')).toBe(big);
  });

  test('reading a missing key is falsy', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(rect.getPluginData('never-set')).toBeFalsy();
  });

  test('local and shared plugin data are isolated', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setPluginData('k', 'local');
    rect.setSharedPluginData('ns', 'k', 'shared');
    expect(rect.getPluginData('k')).toBe('local');
    expect(rect.getSharedPluginData('ns', 'k')).toBe('shared');
  });
});

describe('Local storage', () => {
  test('set, get, keys and remove an item', (ctx) => {
    const ls = ctx.penpot.localStorage;
    ls.setItem('plugin-key', 'plugin-value');
    expect(ls.getItem('plugin-key')).toBe('plugin-value');
    expect(ls.getKeys()).toContain('plugin-key');

    ls.removeItem('plugin-key');
    expect(ls.getItem('plugin-key')).toBeFalsy();
  });
});
