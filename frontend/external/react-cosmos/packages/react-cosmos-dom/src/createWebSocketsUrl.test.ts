import { createWebSocketsUrl } from './createWebSocketsUrl.js';

it('uses "ws" websocket protocol when not on HTTPS', () => {
  expect(createWebSocketsUrl('http://example.com/playground')).toBe(
    'ws://example.com/playground'
  );
});

it('uses "wss" websocket protocol when on HTTPS', () => {
  expect(createWebSocketsUrl('https://example.com/playground')).toBe(
    'wss://example.com/playground'
  );
});
