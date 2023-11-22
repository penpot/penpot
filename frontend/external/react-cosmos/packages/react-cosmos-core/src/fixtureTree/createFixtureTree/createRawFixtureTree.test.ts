import { FixtureList } from '../../userModules/fixtureTypes.js';
import { FixtureTreeNode } from '../types.js';
import { createRawFixtureTree } from './createRawFixtureTree.js';

it('creates tree with fixture', () => {
  const fixtures: FixtureList = {
    'Dashboard.fixture.js': { type: 'single' },
  };
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      'Dashboard.fixture': {
        data: {
          type: 'fixture',
          path: 'Dashboard.fixture.js',
        },
      },
    },
  };
  expect(createRawFixtureTree(fixtures)).toEqual(tree);
});

it('creates nested tree with fixture', () => {
  const fixtures: FixtureList = {
    'ui/Dashboard.fixture.js': { type: 'single' },
  };
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      ui: {
        data: { type: 'fileDir' },
        children: {
          'Dashboard.fixture': {
            data: {
              type: 'fixture',
              path: 'ui/Dashboard.fixture.js',
            },
          },
        },
      },
    },
  };
  expect(createRawFixtureTree(fixtures)).toEqual(tree);
});

it('creates tree with multi fixture', () => {
  const fixtures: FixtureList = {
    'Button.fixture.js': {
      type: 'multi',
      fixtureNames: ['normal', 'disabled'],
    },
  };
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      'Button.fixture': {
        data: {
          type: 'multiFixture',
          path: 'Button.fixture.js',
          names: ['normal', 'disabled'],
        },
      },
    },
  };
  expect(createRawFixtureTree(fixtures)).toEqual(tree);
});

it('creates nested tree with multi fixture', () => {
  const fixtures: FixtureList = {
    'ui/Button.fixture.js': {
      type: 'multi',
      fixtureNames: ['normal', 'disabled'],
    },
  };
  const tree: FixtureTreeNode = {
    data: { type: 'fileDir' },
    children: {
      ui: {
        data: { type: 'fileDir' },
        children: {
          'Button.fixture': {
            data: {
              type: 'multiFixture',
              path: 'ui/Button.fixture.js',
              names: ['normal', 'disabled'],
            },
          },
        },
      },
    },
  };
  expect(createRawFixtureTree(fixtures)).toEqual(tree);
});
