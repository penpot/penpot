import retry from '@skidding/async-retry';
import React from 'react';
import { createValues, uuid } from 'react-cosmos-core';
import { FixtureCapture } from '../fixture/FixtureCapture/FixtureCapture.js';
import { HelloMessage } from '../testHelpers/components.js';
import { anyProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

function Wrap({ children }: { children: () => React.ReactNode }) {
  return <>{children()}</>;
}
Wrap.cosmosCapture = false;

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: (
    <>
      <Wrap>{() => <HelloMessage name="Blanca" />}</Wrap>
      <Wrap>
        {() => (
          <FixtureCapture decoratorId="mockDecoratorId">
            <HelloMessage name="B" />
          </FixtureCapture>
        )}
      </Wrap>
    </>
  ),
});
const fixtureId = { path: 'first' };

testRenderer(
  'captures props from render callback',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await retry(() =>
      expect(renderer.toJSON()).toEqual(['Hello Blanca', 'Hello B'])
    );
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            decoratorId: 'mockDecoratorId',
            values: createValues({ name: 'B' }),
          }),
        ],
      },
    });
  }
);
