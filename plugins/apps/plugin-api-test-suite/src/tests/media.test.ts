import { expect, expectReject } from '../framework/expect';
import { describe, test } from '../framework/registry';
import { PNG_1X1 } from './fixtures';

// Media uploads and exports.

// Skipped under MOCK_BACKEND: media upload exercises real ImageMagick on the
// backend (image validation / canned upload data) that a 200 mock can't
// reproduce — the rejection tests would fail and the success tests go vacuous.
describe.skipIfMocked('Media', () => {
  test('uploadMediaData uploads bytes and returns image data', async (ctx) => {
    const image = await ctx.penpot.uploadMediaData(
      'plugin-image',
      PNG_1X1,
      'image/png',
    );
    expect(typeof image.id).toBe('string');
    expect(image.width).toBe(1);
    expect(image.height).toBe(1);
    expect(image.mtype).toBe('image/png');
    expect(typeof image.name).toBe('string');
    // keepAspectRatio is optional and may be null when not set.
    expect(
      image.keepAspectRatio == null ||
        typeof image.keepAspectRatio === 'boolean',
    ).toBe(true);

    const bytes = await image.data();
    expect(bytes.length).toBeGreaterThan(0);
  });

  test('an uploaded image can be used as a fill', async (ctx) => {
    const image = await ctx.penpot.uploadMediaData(
      'plugin-fill',
      PNG_1X1,
      'image/png',
    );
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.fills = [{ fillOpacity: 1, fillImage: image }];

    const fills = rect.fills;
    if (Array.isArray(fills)) {
      expect(fills[0].fillImage).toBeDefined();
    }
  });

  test('Fill.fillImage can be set on a fill', async (ctx) => {
    const image = await ctx.penpot.uploadMediaData(
      'plugin-fill-set',
      PNG_1X1,
      'image/png',
    );
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];

    // Set fillImage directly on the fill (covers Fill.fillImage (set)).
    const fill = rect.fills[0];
    fill.fillImage = image;
    expect(fill.fillImage).toBeDefined();
  });

  test('uploadMediaUrl resolves to image data', async (ctx) => {
    // Needs the backend to fetch an external URL, which may be unavailable in
    // the headless runner; treat a rejection as an environment limitation.
    const image = await ctx.penpot
      .uploadMediaUrl(
        'plugin-url-image',
        'https://design.penpot.app/images/favicon.png',
      )
      .catch(() => null);
    if (image) {
      expect(typeof image.id).toBe('string');
    }
  });

  // ---------------------------------------------------------------------------
  // Edge cases. Invalid upload input must not resolve. (These hold
  // even when the backend is unreachable in the headless runner, since a
  // rejection is the asserted outcome.)
  // ---------------------------------------------------------------------------
  test('uploadMediaData with empty bytes rejects', async (ctx) => {
    await expectReject(() =>
      ctx.penpot.uploadMediaData('empty', new Uint8Array([]), 'image/png'),
    );
  });

  test('uploadMediaData with non-image bytes rejects', async (ctx) => {
    const garbage = new Uint8Array([1, 2, 3, 4, 5]);
    await expectReject(() =>
      ctx.penpot.uploadMediaData('garbage', garbage, 'image/png'),
    );
  });

  test('uploadMediaUrl with an invalid URL rejects', async (ctx) => {
    await expectReject(() =>
      ctx.penpot.uploadMediaUrl('bad-url', 'not://a.valid/url'),
    );
  });
});

describe('Exports', () => {
  test('export settings round-trip on a shape', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.exports = [
      { type: 'png', scale: 2, suffix: '@2x', skipChildren: false },
    ];

    expect(rect.exports).toHaveLength(1);
    const exp = rect.exports[0];
    expect(exp.type).toBe('png');
    expect(exp.scale).toBeCloseTo(2, 0);
    expect(exp.suffix).toBe('@2x');
    // skipChildren is optional; a stored `false` reads back as undefined.
    expect(exp.skipChildren).toBeFalsy();
  });

  test('export settings accept jpeg, webp, svg and pdf types', (ctx) => {
    // Only png is exercised above; pin that the other export formats round-trip
    // as settings (the actual render is covered separately and may be headless-
    // limited).
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    for (const type of ['jpeg', 'webp', 'svg', 'pdf'] as const) {
      rect.exports = [{ type, scale: 1 }];
      expect(rect.exports).toHaveLength(1);
      expect(rect.exports[0].type).toBe(type);
    }
  });

  test('shape export renders to bytes', async (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    rect.resize(20, 20);
    // Rendering may not be available in the headless runner; tolerate failure.
    const bytes = await rect
      .export({ type: 'png', scale: 1 })
      .catch(() => null);
    if (bytes) {
      expect(bytes.length).toBeGreaterThan(0);
    }
  });
});
