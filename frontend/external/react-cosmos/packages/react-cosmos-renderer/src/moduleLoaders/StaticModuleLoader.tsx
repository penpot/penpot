import React from 'react';
import {
  ByPath,
  FixtureModules,
  ReactDecoratorWrapper,
  ReactFixtureWrapper,
  getSortedDecoratorsForFixturePath,
} from 'react-cosmos-core';

type Props = {
  fixtureWrapper: ReactFixtureWrapper;
  decorators: ByPath<ReactDecoratorWrapper>;
  fixturePath: string;
  renderModules: (modules: FixtureModules) => React.ReactElement;
};
export function StaticModuleLoader({
  fixtureWrapper,
  decorators,
  fixturePath,
  renderModules,
}: Props) {
  return renderModules(
    React.useMemo<FixtureModules>(
      () => ({
        fixtureModule: fixtureWrapper.module,
        decoratorModules: getSortedDecoratorsForFixturePath(
          fixturePath,
          decorators
        ).map(d => d.module),
      }),
      [decorators, fixturePath, fixtureWrapper.module]
    )
  );
}
