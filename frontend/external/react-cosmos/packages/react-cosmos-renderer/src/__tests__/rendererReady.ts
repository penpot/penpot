import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';

const rendererId = uuid();

testRenderer(
  'posts renderer ready',
  { rendererId, fixtures: {} },
  async ({ rendererReady }) => {
    await rendererReady({ rendererId });
  }
);

testRenderer(
  'posts ready response again on ping request',
  { rendererId, fixtures: {} },
  async ({ rendererReady, pingRenderers, clearResponses }) => {
    await rendererReady({ rendererId });
    clearResponses();
    pingRenderers();
    await rendererReady({ rendererId });
  }
);
