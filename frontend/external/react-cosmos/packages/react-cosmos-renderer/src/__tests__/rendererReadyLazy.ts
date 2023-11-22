import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';

const rendererId = uuid();

testRenderer(
  'posts lazy renderer ready',
  { rendererId, fixtures: {}, lazy: true },
  async ({ rendererReady }) => {
    await rendererReady({ rendererId });
  }
);

testRenderer(
  'posts lazy ready response again on ping request',
  { rendererId, fixtures: {}, lazy: true },
  async ({ rendererReady, pingRenderers, clearResponses }) => {
    await rendererReady({ rendererId });
    clearResponses();
    pingRenderers();
    await rendererReady({ rendererId });
  }
);
