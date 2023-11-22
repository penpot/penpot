import React from 'react';
import {
  FixtureModules,
  UserModuleWrappers,
  getSortedDecoratorsForFixturePath,
} from 'react-cosmos-core';
import { importLazyFixtureModules } from './importLazyFixtureModules.js';

type Props = {
  moduleWrappers: UserModuleWrappers;
  fixturePath: string;
  renderModules: (modules: FixtureModules) => React.ReactElement;
};
export async function AsyncModuleLoader({
  moduleWrappers,
  fixturePath,
  renderModules,
}: Props) {
  return renderModules(await getModules(moduleWrappers, fixturePath));
}

async function getModules(
  moduleWrappers: UserModuleWrappers,
  fixturePath: string
): Promise<FixtureModules> {
  if (moduleWrappers.lazy) {
    return await importLazyFixtureModules(
      moduleWrappers.fixtures[fixturePath],
      getSortedDecoratorsForFixturePath(fixturePath, moduleWrappers.decorators)
    );
  }

  return {
    fixtureModule: moduleWrappers.fixtures[fixturePath].module,
    decoratorModules: getSortedDecoratorsForFixturePath(
      fixturePath,
      moduleWrappers.decorators
    ).map(d => d.module),
  };
}
