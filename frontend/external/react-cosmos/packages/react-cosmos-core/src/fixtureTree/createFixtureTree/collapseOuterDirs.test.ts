import { FixtureTreeNode } from '../types.js';
import { collapseOuterDirs } from './collapseOuterDirs.js';

it('collapses one outer dir', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      src: {
        data: { type: 'fileDir' },
        children: {
          Dashboard: {
            data: {
              type: 'fixture',
              path: 'src/Dashboard.fixture.js',
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
          path: 'src/Dashboard.fixture.js',
        },
      },
    },
  };
  expect(collapseOuterDirs(tree)).toEqual(collapsedTree);
});

it('collapses one outer dir (multi fixture)', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      src: {
        data: { type: 'fileDir' },
        children: {
          Button: {
            data: {
              type: 'multiFixture',
              path: 'src/Button.fixture.js',
              names: ['normal', 'disabled'],
            },
          },
        },
      },
    },
  };
  const collapsedTree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      Button: {
        data: {
          type: 'multiFixture',
          path: 'src/Button.fixture.js',
          names: ['normal', 'disabled'],
        },
      },
    },
  };
  expect(collapseOuterDirs(tree)).toEqual(collapsedTree);
});

it('collapses multiple outer dirs', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      src: {
        data: { type: 'fileDir' },
        children: {
          ui: {
            data: { type: 'fileDir' },
            children: {
              admin: {
                data: { type: 'fileDir' },
                children: {
                  Dashboard: {
                    data: {
                      type: 'fixture',
                      path: 'src/ui/admin/Dashboard.fixture.js',
                    },
                  },
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
      Dashboard: {
        data: {
          type: 'fixture',
          path: 'src/ui/admin/Dashboard.fixture.js',
        },
      },
    },
  };
  expect(collapseOuterDirs(tree)).toEqual(collapsedTree);
});
