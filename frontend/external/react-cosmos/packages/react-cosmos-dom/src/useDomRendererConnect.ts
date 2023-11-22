'use client';
import React from 'react';
import { isInsideWindowIframe } from 'react-cosmos-core';
import {
  createNoopRendererConnect,
  createPostMessageConnect,
  createWebSocketsConnect,
} from 'react-cosmos-renderer';
import { createWebSocketsUrl } from './createWebSocketsUrl.js';

export function useDomRendererConnect(playgroundUrl: string) {
  return React.useMemo(
    () => createDomRendererConnect(playgroundUrl),
    [playgroundUrl]
  );
}

function createDomRendererConnect(playgroundUrl: string) {
  // TODO: Don't try to connect to WS in static exports.
  if (typeof window === 'undefined') {
    return createNoopRendererConnect();
  } else {
    return isInsideWindowIframe()
      ? createPostMessageConnect()
      : createWebSocketsConnect(createWebSocketsUrl(playgroundUrl));
  }
}
