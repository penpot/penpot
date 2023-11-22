import React from 'react';
import { createValue, createValues, uuid } from 'react-cosmos-core';
import { Wrapper } from '../testHelpers/components.js';
import { anyProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: <Wrapper>yo</Wrapper>,
});
const fixtureId = { path: 'first' };

testRenderer(
  'transitions string children into an element with children',
  { rendererId, fixtures },
  async ({ update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: createValues({ children: 'yo' }),
          }),
        ],
      },
    });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: (
          <Wrapper>
            <Wrapper>brah</Wrapper>
          </Wrapper>
        ),
      }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: {
              children: {
                type: 'unserializable',
                stringifiedData: `<React.Element />`,
              },
            },
          }),
          anyProps({
            values: createValues({ children: 'brah' }),
          }),
        ],
      },
    });
  }
);

testRenderer(
  'transitions string children into an element with children',
  { rendererId, fixtures },
  async ({ update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: createValues({ children: 'yo' }),
          }),
        ],
      },
    });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: (
          <Wrapper>
            <Wrapper>brah</Wrapper>
          </Wrapper>
        ),
      }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: {
              children: {
                type: 'unserializable',
                stringifiedData: `<React.Element />`,
              },
            },
          }),
          anyProps({
            values: createValues({ children: 'brah' }),
          }),
        ],
      },
    });
  }
);

testRenderer(
  'transitions string children into an element with multiple children',
  { rendererId, fixtures },
  async ({ update, selectFixture, fixtureStateChange }) => {
    selectFixture({
      rendererId,
      fixtureId,
      fixtureState: {},
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: createValues({ children: 'yo' }),
          }),
        ],
      },
    });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: (
          <Wrapper>
            <Wrapper>brah</Wrapper>
            <Wrapper>brah</Wrapper>
          </Wrapper>
        ),
      }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: {
              children: {
                type: 'array',
                values: [
                  createValue(<Wrapper>brah</Wrapper>),
                  createValue(<Wrapper>brah</Wrapper>),
                ],
              },
            },
          }),
          anyProps({
            values: createValues({ children: 'brah' }),
          }),
          anyProps({
            values: createValues({ children: 'brah' }),
          }),
        ],
      },
    });
  }
);
