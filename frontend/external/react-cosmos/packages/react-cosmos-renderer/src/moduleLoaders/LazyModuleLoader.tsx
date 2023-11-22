import React from 'react';
import {
  ByPath,
  FixtureModules,
  LazyReactDecoratorWrapper,
  LazyReactFixtureWrapper,
  getSortedDecoratorsForFixturePath,
} from 'react-cosmos-core';
import { importLazyFixtureModules } from './importLazyFixtureModules.js';

type Props = {
  fixtureWrapper: LazyReactFixtureWrapper;
  decorators: ByPath<LazyReactDecoratorWrapper>;
  fixturePath: string;
  renderModules: (modules: FixtureModules) => React.ReactElement;
};
export function LazyModuleLoader({
  fixtureWrapper,
  decorators,
  fixturePath,
  renderModules,
}: Props) {
  const modules = useLazyFixtureModules(
    fixturePath,
    fixtureWrapper,
    decorators
  );

  return modules && renderModules(modules);
}

type State = { fixturePath: string; modules: FixtureModules } | null;

function useLazyFixtureModules(
  fixturePath: string,
  fixtureWrapper: LazyReactFixtureWrapper,
  decoratorWrappers: ByPath<LazyReactDecoratorWrapper>
) {
  const [state, setState] = React.useState<State>(null);

  React.useEffect(() => {
    let canceled = false;

    (async () => {
      const modules = await importLazyFixtureModules(
        fixtureWrapper,
        getSortedDecoratorsForFixturePath(fixturePath, decoratorWrappers)
      );

      if (!canceled) {
        setState({ fixturePath, modules });
      }
    })();

    return () => {
      canceled = true;
    };
  }, [decoratorWrappers, fixturePath, fixtureWrapper]);

  // Stop returning modules once fixturePath changed to prevent rendering
  // the previous fixture until the new fixture modules are loaded
  return state && state.fixturePath === fixturePath ? state.modules : null;
}
