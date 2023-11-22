import { FixtureList } from '../../userModules/fixtureTypes.js';
import { sortTreeChildren } from '../../utils/tree.js';
import { FixtureTreeNode } from '../types.js';
import { collapseFixtureDirs } from './collapseFixtureDirs.js';
import { collapseIndexes } from './collapseIndexes.js';
import { collapseNamedIndexes } from './collapseNamedIndexes.js';
import { collapseOuterDirs } from './collapseOuterDirs.js';
import { createRawFixtureTree } from './createRawFixtureTree.js';
import { hideFixtureSuffix } from './hideFixtureSuffix.js';

export function createFixtureTree({
  fixtures,
  fixturesDir,
  fixtureFileSuffix,
}: {
  fixtures: FixtureList;
  fixturesDir: string;
  fixtureFileSuffix: string;
}): FixtureTreeNode {
  let tree = createRawFixtureTree(fixtures);
  tree = collapseFixtureDirs(tree, fixturesDir);
  tree = hideFixtureSuffix(tree, fixtureFileSuffix);
  tree = collapseIndexes(tree);
  tree = collapseNamedIndexes(tree);
  tree = collapseOuterDirs(tree);
  return sortTreeChildren(tree);
}
