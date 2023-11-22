import {
  LazyReactDecoratorWrapper,
  LazyReactFixtureWrapper,
} from 'react-cosmos-core';

export async function importLazyFixtureModules(
  fixtureWrapper: LazyReactFixtureWrapper,
  decoratorWrappers: LazyReactDecoratorWrapper[]
) {
  return {
    fixtureModule: await fixtureWrapper.getModule(),
    decoratorModules: await Promise.all(
      decoratorWrappers.map(d => d.getModule())
    ),
  };
}
