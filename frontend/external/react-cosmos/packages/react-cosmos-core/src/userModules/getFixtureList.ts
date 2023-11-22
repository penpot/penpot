import { FixtureList, FixtureListItem } from './fixtureTypes.js';
import { isMultiFixture } from './isMultiFixture.js';
import {
  ByPath,
  ReactFixtureExport,
  UserModuleWrappers,
} from './userModuleTypes.js';

export function getFixtureListFromWrappers(wrappers: UserModuleWrappers) {
  return Object.keys(wrappers.fixtures).reduce<FixtureList>(
    (acc, fixturePath) => {
      return {
        ...acc,
        [fixturePath]: wrappers.lazy
          ? { type: 'single' }
          : getFixtureItemFromExport(
              wrappers.fixtures[fixturePath].module.default
            ),
      };
    },
    {}
  );
}

export function getFixtureListFromExports(exports: ByPath<ReactFixtureExport>) {
  return Object.keys(exports).reduce((acc: FixtureList, fixturePath) => {
    return {
      ...acc,
      [fixturePath]: getFixtureItemFromExport(exports[fixturePath]),
    };
  }, {});
}

export function getFixtureItemFromExport(
  fixtureExport: ReactFixtureExport
): FixtureListItem {
  return isMultiFixture(fixtureExport)
    ? { type: 'multi', fixtureNames: Object.keys(fixtureExport) }
    : { type: 'single' };
}
