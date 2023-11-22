import { FixtureTreeNode } from '../types.js';
import { collapseNamedIndexes } from './collapseNamedIndexes.js';

it('collapses named index fixture', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      Dashboard: {
        data: { type: 'fileDir' },
        children: {
          Dashboard: {
            data: {
              type: 'fixture',
              path: 'Dashboard/Dashboard.fixture.js',
            },
          },
        },
      },
    },
  };
  const collapsedTree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      Dashboard: {
        data: {
          type: 'fixture',
          path: 'Dashboard/Dashboard.fixture.js',
        },
      },
    },
  };
  expect(collapseNamedIndexes(tree)).toEqual(collapsedTree);
});

it('collapses nested named index fixture', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      ui: {
        data: { type: 'fileDir' },
        children: {
          Dashboard: {
            data: { type: 'fileDir' },
            children: {
              Dashboard: {
                data: {
                  type: 'fixture',
                  path: 'ui/Dashboard/Dashboard.fixture.js',
                },
              },
            },
          },
        },
      },
    },
  };
  const collapsedTree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      ui: {
        data: { type: 'fileDir' },
        children: {
          Dashboard: {
            data: {
              type: 'fixture',
              path: 'ui/Dashboard/Dashboard.fixture.js',
            },
          },
        },
      },
    },
  };
  expect(collapseNamedIndexes(tree)).toEqual(collapsedTree);
});

it('collapses named index fixture (case insensitive)', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      dashboard: {
        data: { type: 'fileDir' },
        children: {
          Dashboard: {
            data: {
              type: 'fixture',
              path: 'dashboard/Dashboard.fixture.js',
            },
          },
        },
      },
    },
  };
  const collapsedTree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      Dashboard: {
        data: {
          type: 'fixture',
          path: 'dashboard/Dashboard.fixture.js',
        },
      },
    },
  };
  expect(collapseNamedIndexes(tree)).toEqual(collapsedTree);
});
