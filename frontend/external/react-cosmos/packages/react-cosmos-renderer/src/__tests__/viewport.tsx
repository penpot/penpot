import retry from '@skidding/async-retry';
import React from 'react';
import { uuid } from 'react-cosmos-core';
import { Viewport } from '../fixture/Viewport.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: (
    <Viewport width={320} height={240}>
      yo
    </Viewport>
  ),
});
const fixtureId = { path: 'first' };

testRenderer(
  'renders children',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId,
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('yo'));
  }
);

testRenderer(
  'creates viewport fixture state',
  { rendererId, fixtures },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({
      rendererId,
      fixtureId,
      fixtureState: {},
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [],
        viewport: { width: 320, height: 240 },
      },
    });
  }
);

testRenderer(
  'updates viewport fixture state',
  { rendererId, fixtures },
  async ({ update, selectFixture, fixtureStateChange }) => {
    selectFixture({
      rendererId,
      fixtureId,
      fixtureState: {},
    });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: (
          <Viewport width={640} height={480}>
            yo
          </Viewport>
        ),
      }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [],
        viewport: { width: 640, height: 480 },
      },
    });
  }
);
