import { FixtureList } from '../userModules/fixtureTypes.js';
import { createFixtureTree } from './createFixtureTree/index.js';
import { flattenFixtureTree } from './flattenFixtureTree.js';

const fixtures: FixtureList = {
  'src/__fixtures__/Profile.ts': { type: 'single' },
  'src/__fixtures__/NewsFeed.ts': { type: 'single' },
  'src/admin/Dashboard/index.fixture.ts': {
    type: 'multi',
    fixtureNames: ['overview', 'stats'],
  },
};

const fixtureTree = createFixtureTree({
  fixtures,
  fixturesDir: '__fixtures__',
  fixtureFileSuffix: 'fixture',
});

it('flattens fixture tree', () => {
  expect(flattenFixtureTree(fixtureTree)).toEqual([
    {
      fileName: 'Dashboard',
      fixtureId: {
        path: 'src/admin/Dashboard/index.fixture.ts',
        name: 'overview',
      },
      name: 'overview',
      parents: ['admin'],
    },
    {
      fileName: 'Dashboard',
      fixtureId: {
        path: 'src/admin/Dashboard/index.fixture.ts',
        name: 'stats',
      },
      name: 'stats',
      parents: ['admin'],
    },
    {
      fileName: 'NewsFeed',
      fixtureId: { path: 'src/__fixtures__/NewsFeed.ts' },
      name: null,
      parents: [],
    },
    {
      fileName: 'Profile',
      fixtureId: { path: 'src/__fixtures__/Profile.ts' },
      name: null,
      parents: [],
    },
  ]);
});
