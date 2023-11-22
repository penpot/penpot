export function createWebSocketsUrl(playgroundUrl: string) {
  return playgroundUrl.replace(/^https:/, 'wss:').replace(/^http:/, 'ws:');
}
