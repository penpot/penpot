import { isMultiFixture } from './isMultiFixture.js';
import { ReactFixture, ReactFixtureExport } from './userModuleTypes.js';

export function getFixtureFromExport(
  fixtureExport: ReactFixtureExport,
  fixtureName?: string
): void | ReactFixture {
  if (fixtureName === undefined) {
    if (isMultiFixture(fixtureExport)) {
      // Fixture name missing in multi fixture
      const fixtureNames = Object.keys(fixtureExport);
      return fixtureExport[fixtureNames[0]];
    }

    return fixtureExport;
  }

  if (!isMultiFixture(fixtureExport)) {
    // Fixture name not found in single fixture
    return;
  }

  return fixtureExport[fixtureName];
}
