import { FixtureTreeNode } from '../types.js';
import { collapseFixtureDirs } from './collapseFixtureDirs.js';

it('collapses fixtures dir', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      ui: {
        data: { type: 'fileDir' },
        children: {
          __fixtures__: {
            data: { type: 'fileDir' },
            children: {
              shared: {
                data: { type: 'fileDir' },
                children: {
                  Button: {
                    data: {
                      type: 'multiFixture',
                      path: 'ui/__fixtures__/shared/Button.js',
                      names: ['normal', 'disabled'],
                    },
                  },
                },
              },
              Dashboard: {
                data: {
                  type: 'fixture',
                  path: 'ui/__fixtures__/Dashboard.js',
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
          shared: {
            data: { type: 'fileDir' },
            children: {
              Button: {
                data: {
                  type: 'multiFixture',
                  path: 'ui/__fixtures__/shared/Button.js',
                  names: ['normal', 'disabled'],
                },
              },
            },
          },
          Dashboard: {
            data: {
              type: 'fixture',
              path: 'ui/__fixtures__/Dashboard.js',
            },
          },
        },
      },
    },
  };
  expect(collapseFixtureDirs(tree, '__fixtures__')).toEqual(collapsedTree);
});

it('collapses fixtures dir with sibling', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      ui: {
        data: { type: 'fileDir' },
        children: {
          __fixtures__: {
            data: { type: 'fileDir' },
            children: {
              Dashboard: {
                data: {
                  type: 'fixture',
                  path: 'ui/__fixtures__/Dashboard.js',
                },
              },
            },
          },
          shared: {
            data: { type: 'fileDir' },
            children: {
              Button: {
                data: {
                  type: 'fixture',
                  path: 'ui/shared/Button.fixture.js',
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
          shared: {
            data: { type: 'fileDir' },
            children: {
              Button: {
                data: {
                  type: 'fixture',
                  path: 'ui/shared/Button.fixture.js',
                },
              },
            },
          },
          Dashboard: {
            data: {
              type: 'fixture',
              path: 'ui/__fixtures__/Dashboard.js',
            },
          },
        },
      },
    },
  };
  expect(collapseFixtureDirs(tree, '__fixtures__')).toEqual(collapsedTree);
});

it('collapses fixtures dirs at different levels', () => {
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      __fixtures__: {
        data: { type: 'fileDir' },
        children: {
          Dashboard: {
            data: {
              type: 'fixture',
              path: '__fixtures__/Dashboard.js',
            },
          },
        },
      },
      shared: {
        data: { type: 'fileDir' },
        children: {
          __fixtures__: {
            data: { type: 'fileDir' },
            children: {
              Button: {
                data: {
                  type: 'fixture',
                  path: 'shared/__fixtures__/Button.js',
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
          path: '__fixtures__/Dashboard.js',
        },
      },
      shared: {
        data: { type: 'fileDir' },
        children: {
          Button: {
            data: {
              type: 'fixture',
              path: 'shared/__fixtures__/Button.js',
            },
          },
        },
      },
    },
  };
  expect(collapseFixtureDirs(tree, '__fixtures__')).toEqual(collapsedTree);
});
