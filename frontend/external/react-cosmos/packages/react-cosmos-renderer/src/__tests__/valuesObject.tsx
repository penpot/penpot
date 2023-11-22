import retry from '@skidding/async-retry';
import React from 'react';
import { createValue, uuid } from 'react-cosmos-core';
import {
  ReactTestRenderer,
  ReactTestRendererJSON,
  act,
} from 'react-test-renderer';
import { useValue } from '../fixture/useValue/index.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

type Profile = {
  isAdmin: boolean;
  name: string;
  age: number;
  onClick: () => unknown;
};

function createFixtures({ defaultValue }: { defaultValue: Profile }) {
  const MyComponent = () => {
    const [profile, setProfile] = useValue('profile', { defaultValue });
    return (
      <>
        <p>{JSON.stringify(profile, null, 2)}</p>
        <button
          onClick={() => setProfile({ ...profile, isAdmin: !profile.isAdmin })}
        >
          Toggle admin
        </button>
      </>
    );
  };
  return wrapDefaultExport({
    first: <MyComponent />,
  });
}

const rendererId = uuid();
const fixtures = createFixtures({
  defaultValue: { isAdmin: true, name: 'Pat D', age: 45, onClick: () => {} },
});
const fixtureId = { path: 'first' };

testRenderer(
  'renders fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, { isAdmin: true, name: 'Pat D', age: 45 });
  }
);

testRenderer(
  'creates fixture state',
  { rendererId, fixtures },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          profile: {
            type: 'standard',
            defaultValue: createValue({
              isAdmin: true,
              name: 'Pat D',
              age: 45,
              onClick: () => {},
            }),
            currentValue: createValue({
              isAdmin: true,
              name: 'Pat D',
              age: 45,
              onClick: () => {},
            }),
          },
        },
      },
    });
  }
);

testRenderer(
  'updates fixture state via setter',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, { isAdmin: true, name: 'Pat D', age: 45 });
    toggleAdminButton(renderer);
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          profile: {
            type: 'standard',
            defaultValue: createValue({
              isAdmin: true,
              name: 'Pat D',
              age: 45,
              onClick: () => {},
            }),
            currentValue: createValue({
              isAdmin: false,
              name: 'Pat D',
              age: 45,
              onClick: () => {},
            }),
          },
        },
      },
    });
  }
);

testRenderer(
  'resets fixture state on default value change',
  { rendererId, fixtures },
  async ({ renderer, update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, { isAdmin: true, name: 'Pat D', age: 45 });
    update({
      rendererId,
      fixtures: createFixtures({
        defaultValue: {
          isAdmin: false,
          name: 'Pat D',
          age: 45,
          onClick: () => {},
        },
      }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          profile: {
            type: 'standard',
            defaultValue: createValue({
              isAdmin: false,
              name: 'Pat D',
              age: 45,
              onClick: () => {},
            }),
            currentValue: createValue({
              isAdmin: false,
              name: 'Pat D',
              age: 45,
              onClick: () => {},
            }),
          },
        },
      },
    });
  }
);

async function rendered(
  renderer: ReactTestRenderer,
  profile: Pick<Profile, 'isAdmin' | 'name' | 'age'>
) {
  await retry(() => {
    const profileText = getProfileText(getProfileNode(renderer));
    expect(profileText).toMatch(`"isAdmin": ${profile.isAdmin}`);
    expect(profileText).toMatch(`"name": "${profile.name}"`);
    expect(profileText).toMatch(`"age": ${profile.age}`);
  });
}

function toggleAdminButton(renderer: ReactTestRenderer) {
  act(() => {
    getButtonNode(renderer).props.onClick();
  });
}

function getProfileNode(renderer: ReactTestRenderer) {
  return (renderer.toJSON() as any)[0] as ReactTestRendererJSON;
}

function getButtonNode(renderer: ReactTestRenderer) {
  return (renderer.toJSON() as any)[1] as ReactTestRendererJSON;
}

function getProfileText(rendererNode: ReactTestRendererJSON) {
  return rendererNode.children!.join('');
}
