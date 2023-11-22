import React from 'react';
import { ClassStateMock, createValues, uuid } from 'react-cosmos-core';
import { Counter } from '../testHelpers/components.js';
import { anyClassState, anyProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: (
    <ClassStateMock state={{ count: 5 }}>
      <Counter />
    </ClassStateMock>
  ),
});
const fixtureId = { path: 'first' };

// NOTE: This is a regression test that was created for a bug that initally
// slipped unnoticed in https://github.com/react-cosmos/react-cosmos/pull/893.
// Because element refs from unmounted FixtureCapture instances were
// incorrectly reused, component state was no longer picked up after
// FixtureCapture remounted. This was related to the refactor of
// FixtureCapture/attachChildRefs in
// https://github.com/react-cosmos/react-cosmos/commit/56494b6ea10785cc3db8dda7a7fbcad62c8e1c12
testRenderer(
  'captures initial state after re-selecting fixture',
  { rendererId, fixtures },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [anyProps()],
        classState: [
          anyClassState({
            values: createValues({ count: 5 }),
          }),
        ],
      },
    });
  }
);
