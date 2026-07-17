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
      expect(file.getPluginDataKeys()).toContain('fileKey');
    }
  });

  test('shared plugin data round-trips on the file', (ctx) => {
    const file = ctx.penpot.currentFile;
    expect(file).not.toBeNull();
    if (file) {
      file.setSharedPluginData('ns', 'fileShared', 'fileSharedValue');
      expect(file.getSharedPluginData('ns', 'fileShared')).toBe(
        'fileSharedValue',
      );
      expect(file.getSharedPluginDataKeys('ns')).toContain('fileShared');
    }
  });

  test('plugin data round-trips on a page', (ctx) => {
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      page.setPluginData('pageKey', 'pageValue');
      expect(page.getPluginData('pageKey')).toBe('pageValue');
      expect(page.getPluginDataKeys()).toContain('pageKey');
    }
  });

  test('shared plugin data round-trips on a page', (ctx) => {
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      page.setSharedPluginData('ns', 'pageShared', 'pageSharedValue');
      expect(page.getSharedPluginData('ns', 'pageShared')).toBe(
        'pageSharedValue',
      );
      expect(page.getSharedPluginDataKeys('ns')).toContain('pageShared');
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

  // Keys are opaque strings: no case normalization is applied. A camelCase key
  // and its kebab-case spelling are distinct entries that each round-trip
  // independently. Pins that behaviour so a future key-normalization regression
  // (reported as camelCase keys "behaving incorrectly") would be caught here.
  test('camelCase and kebab-case keys are distinct and both round-trip', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setPluginData('myKey', 'camel');
    rect.setPluginData('my-key', 'kebab');
    expect(rect.getPluginData('myKey')).toBe('camel');
    expect(rect.getPluginData('my-key')).toBe('kebab');
    const keys = rect.getPluginDataKeys();
    expect(keys).toContain('myKey');
    expect(keys).toContain('my-key');
  });

  // Same guarantee at the file-data storage location (a distinct code path from
  // shape/page objects), covering a camelCase key on the file itself.
  test('a camelCase key round-trips on the file', (ctx) => {
    const file = ctx.penpot.currentFile;
    expect(file).not.toBeNull();
    if (file) {
      file.setPluginData('camelCaseFileKey', 'value');
      expect(file.getPluginData('camelCaseFileKey')).toBe('value');
      expect(file.getPluginDataKeys()).toContain('camelCaseFileKey');
    }
  });

  // camelCase is likewise preserved in the shared namespace and the shared key.
  test('camelCase shared namespace and key round-trip', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.setSharedPluginData('myNamespace', 'myKey', 'camel');
    rect.setSharedPluginData('myNamespace', 'my-key', 'kebab');
    expect(rect.getSharedPluginData('myNamespace', 'myKey')).toBe('camel');
    expect(rect.getSharedPluginData('myNamespace', 'my-key')).toBe('kebab');
    const keys = rect.getSharedPluginDataKeys('myNamespace');
    expect(keys).toContain('myKey');
    expect(keys).toContain('my-key');
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
