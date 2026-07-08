// Shared test fixtures. Not a `*.test.ts`, so the runner's glob doesn't pick it
// up as a test file; it's only imported by the tests that need it.

// A valid 1x1 PNG (opaque red, RGBA), so uploadMediaData needs no network. The
// bytes must form a well-formed PNG — the backend processes the image with
// ImageMagick, which rejects a malformed IDAT chunk (bad CRC / extra data).
export const PNG_1X1 = new Uint8Array([
  137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0,
  0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137, 0, 0, 0, 13, 73, 68, 65, 84, 120,
  156, 99, 248, 207, 192, 240, 31, 0, 5, 0, 1, 255, 137, 153, 61, 29, 0, 0, 0,
  0, 73, 69, 78, 68, 174, 66, 96, 130,
]);
