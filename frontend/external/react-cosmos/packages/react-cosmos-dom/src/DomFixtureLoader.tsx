import React from 'react';
import { RendererConfig, UserModuleWrappers } from 'react-cosmos-core';
import { ClientFixtureLoader } from 'react-cosmos-renderer/client';
import { DomRendererProvider } from './DomRendererProvider.js';
import { ErrorCatch } from './ErrorCatch.js';

type Props = {
  rendererConfig: RendererConfig;
  moduleWrappers: UserModuleWrappers;
};
export function DomFixtureLoader({ rendererConfig, moduleWrappers }: Props) {
  return (
    <DomRendererProvider rendererConfig={rendererConfig}>
      <ClientFixtureLoader
        moduleWrappers={moduleWrappers}
        globalDecorators={globalDecorators}
        renderMessage={renderMessage}
      />
    </DomRendererProvider>
  );
}

const globalDecorators = [ErrorCatch];

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  top: 0,
  bottom: 0,
  left: 0,
  right: 0,
  display: 'flex',
  justifyContent: 'center',
  alignItems: 'center',
  fontFamily:
    '-apple-system, BlinkMacSystemFont, Ubuntu, "Helvetica Neue", Helvetica, sans-serif',
  fontSize: 14,
};

function renderMessage(msg: string) {
  return <div style={containerStyle}>{msg}</div>;
}
