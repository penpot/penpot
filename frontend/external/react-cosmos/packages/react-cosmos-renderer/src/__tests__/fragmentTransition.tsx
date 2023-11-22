import React from 'react';
import { ClassStateMock, createValues, uuid } from 'react-cosmos-core';
import { Counter } from '../testHelpers/components.js';
import { anyClassState, anyProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: (
    <>
      <ClassStateMock state={{ count: 5 }}>
        <Counter />
      </ClassStateMock>
    </>
  ),
});
const fixtureId = { path: 'first' };

testRenderer(
  'transitions Fragment from single to multi children',
  { rendererId, fixtures },
  async ({ update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        // This is a very tricky case. When fragments have one child,
        // props.children will be that child. But when fragments have
        // two or more children, props.children will be an array. When
        // transitioning from one Fragment child to more (or viceversa)
        // the first child's path changes
        //   - from: props.children
        //   - to: props.children[0]
        // This leads to a messy situation if we don't do proper cleanup.
        first: (
          <>
            <ClassStateMock state={{ count: 5 }}>
              <Counter />
            </ClassStateMock>
            <ClassStateMock state={{ count: 10 }}>
              <Counter />
            </ClassStateMock>
          </>
        ),
      }),
    });
    // Do not remove this line: It captures a regression regarding an error
    // that occurred when component state was read asynchronously
    await new Promise(res => setTimeout(res, 500));
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({ values: createValues({}) }),
          anyProps({ values: createValues({}) }),
        ],
        classState: [
          anyClassState({
            values: createValues({ count: 5 }),
          }),
          anyClassState({
            values: createValues({ count: 10 }),
          }),
        ],
      },
    });
  }
);
