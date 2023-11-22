import retry from '@skidding/async-retry';
import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapActSetTimeout } from '../testHelpers/wrapActSetTimeout.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({ first: null, second: null });

beforeAll(wrapActSetTimeout);

testRenderer(
  'renders blank state message',
  { rendererId, fixtures },
  async ({ renderer }) => {
    await retry(() =>
      expect(renderer.toJSON()).toEqual('No fixture selected.')
    );
  }
);

testRenderer(
  'posts fixture list',
  { rendererId, fixtures },
  async ({ fixtureListUpdate }) => {
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
  }
);

testRenderer(
  'posts fixture list again on ping request',
  { rendererId, fixtures },
  async ({ fixtureListUpdate, pingRenderers, clearResponses }) => {
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
    clearResponses();
    pingRenderers();
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
  }
);

testRenderer(
  'posts updated fixture list on "fixtures" prop change',
  { rendererId, fixtures },
  async ({ update, fixtureListUpdate }) => {
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
    update({
      rendererId,
      fixtures: { ...fixtures, ...wrapDefaultExport({ third: null }) },
    });
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
        third: { type: 'single' },
      },
    });
  }
);
