import { FixtureTreeNode } from '../types.js';

export function collapseOuterDirs(treeNode: FixtureTreeNode): FixtureTreeNode {
  const { children } = treeNode;
  if (!children) return treeNode;

  const childNames = Object.keys(children);
  const containsSingleChild = Object.keys(children).length === 1;
  if (containsSingleChild) {
    const singleChildNode = children[childNames[0]];
    if (singleChildNode.data.type === 'fileDir')
      return collapseOuterDirs(singleChildNode);
  }

  return treeNode;
}
