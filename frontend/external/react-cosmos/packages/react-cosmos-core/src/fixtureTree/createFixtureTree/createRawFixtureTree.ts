import { get, set } from 'lodash-es';
import {
  FixtureList,
  FixtureListItem,
} from '../../userModules/fixtureTypes.js';
import { addTreeNodeChild } from '../../utils/tree.js';
import { FixtureTreeNode } from '../types.js';

export function createRawFixtureTree(fixtures: FixtureList): FixtureTreeNode {
  const rootNode: FixtureTreeNode = {
    data: { type: 'fileDir' },
  };

  Object.keys(fixtures).forEach(fixturePath =>
    addFixturePathToTree(rootNode, fixturePath, fixtures[fixturePath])
  );

  return rootNode;
}

function addFixturePathToTree(
  rootNode: FixtureTreeNode,
  fixturePath: string,
  fixtureItem: FixtureListItem
) {
  const { parents, fileName } = parseFixturePath(fixturePath);

  if (fixtureItem.type == 'single') {
    injectNode(rootNode, parents, fileName, {
      data: {
        type: 'fixture',
        path: fixturePath,
      },
    });
  } else if (fixtureItem.type == 'multi') {
    injectNode(rootNode, parents, fileName, {
      data: {
        type: 'multiFixture',
        path: fixturePath,
        names: fixtureItem.fixtureNames,
      },
    });
  }
}

function parseFixturePath(fixturePath: string) {
  const parents = fixturePath.split('/');

  const rawFixtureName = parents.pop();
  if (!rawFixtureName) throw new Error('Fixture name is empty');

  return {
    parents,
    fileName: removeFixtureNameExtension(rawFixtureName),
  };
}

function removeFixtureNameExtension(fixtureName: string) {
  return fixtureName.replace(/\.(js|jsx|ts|tsx|md|mdx)$/, '');
}

function injectNode(
  rootNode: FixtureTreeNode,
  parents: string[],
  childName: string,
  childNode: FixtureTreeNode
) {
  if (parents.length === 0)
    return addTreeNodeChild(rootNode, childName, childNode);

  let curParentDepth = 1;
  let curParent: FixtureTreeNode;
  do {
    const curParents = parents.slice(0, curParentDepth);
    const curPath = curParents.map(p => `children["${p}"]`).join('.');

    curParent = get(rootNode, curPath);
    if (!curParent) {
      curParent = {
        data: { type: 'fileDir' },
      };
      set(rootNode, curPath, curParent);
    }

    curParentDepth += 1;
  } while (curParentDepth <= parents.length);

  addTreeNodeChild(curParent, childName, childNode);
}
