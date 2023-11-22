import { FixtureId } from './fixtureTypes.js';

export function stringifyFixtureId(fixtureId: FixtureId) {
  const { path, name } = fixtureId;
  return name ? `${path}-${name}` : path;
}
