import React from 'react';
import { createValues, uuid } from 'react-cosmos-core';
import { anyProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtureId = { path: 'first' };

testRenderer(
  'collects fixture state for interesting string element type',
  {
    rendererId,
    fixtures: wrapDefaultExport({ first: <input type="text" /> }),
  },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            componentName: 'input',
            values: createValues({ type: 'text' }),
          }),
        ],
      },
    });
  }
);

testRenderer(
  'collects no fixture state for uninteresting string element type',
  { rendererId, fixtures: wrapDefaultExport({ first: <div>yo</div> }) },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [],
      },
    });
  }
);
