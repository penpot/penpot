import retry from '@skidding/async-retry';
import React from 'react';
import { createValues, updateFixtureStateProps, uuid } from 'react-cosmos-core';
import { getProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtureId = { path: 'first' };

type Props = {
  obj: {};
  cb: (obj: {}) => unknown;
};

function TestComponent({ obj, cb }: Props) {
  cb(obj);
  return null;
}

function createFixtures(obj: {}, cb: (newObj: {}) => unknown) {
  return wrapDefaultExport({
    first: <TestComponent obj={obj} cb={cb} />,
  });
}

testRenderer(
  'preserves object reference',
  { rendererId, fixtures: {} },
  async ({ update, selectFixture }) => {
    const obj = {};
    const cb = jest.fn();
    update({ rendererId, fixtures: createFixtures(obj, cb) });
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await retry(() => expect(getLastMockCall(cb)[0]).toBe(obj));
  }
);

testRenderer(
  'preserves updated object reference',
  { rendererId, fixtures: {} },
  async ({ update, selectFixture, getLastFixtureState, setFixtureState }) => {
    const obj = {};
    const cb = jest.fn();
    update({ rendererId, fixtures: createFixtures(obj, cb) });
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        props: updateFixtureStateProps({
          fixtureState,
          elementId,
          values: createValues({ obj: { name: 'Tim' }, cb }),
        }),
      },
    });
    await retry(() => expect(cb).lastCalledWith({ name: 'Tim' }));
    const updatedObj: {} = getLastMockCall(cb)[0];
    update({ rendererId, fixtures: createFixtures(obj, cb) });
    await retry(() => expect(getLastMockCall(cb)[0]).toBe(updatedObj));
  }
);

function getLastMockCall(mockFn: jest.Mock) {
  // This helper is required because mockFn.lastCalledWith checks deep equality
  // instead of reference equality, which we need in this test
  const { calls } = mockFn.mock;
  return calls[calls.length - 1];
}
