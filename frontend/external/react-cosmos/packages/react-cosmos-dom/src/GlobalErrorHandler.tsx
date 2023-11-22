'use client';
import React from 'react';
import { RendererContext } from 'react-cosmos-renderer/client';

export function GlobalErrorHandler() {
  const { rendererId, rendererConnect } = React.useContext(RendererContext);

  React.useEffect(() => {
    function handleGlobalError() {
      rendererConnect.postMessage({
        type: 'rendererError',
        payload: { rendererId },
      });
    }
    // Unhandled errors from async code will not be caught by the error event, but
    // the unhandledrejection event instead.
    window.addEventListener('error', handleGlobalError);
    window.addEventListener('unhandledrejection', handleGlobalError);

    return () => {
      window.removeEventListener('error', handleGlobalError);
      window.removeEventListener('unhandledrejection', handleGlobalError);
    };
  }, [rendererConnect, rendererId]);

  return null;
}
