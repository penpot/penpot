import { omit } from 'lodash-es';
import { FixtureTreeNode } from '../types.js';

export function collapseFixtureDirs(
  treeNode: FixtureTreeNode,
  fixturesDir: string
): FixtureTreeNode {
  const { data, children } = treeNode;
  if (data.type !== 'fileDir' || !children) return treeNode;

  const collapsableDirNode = children[fixturesDir];
  if (collapsableDirNode && collapsableDirNode.data.type === 'fileDir') {
    const otherChildren = omit(children, fixturesDir);
    const innerChildren = collapsableDirNode.children;
    // Make sure children of the collapsed dir don't overlap with children of
    // the parent dir
    const collapsable =
      innerChildren &&
      Object.keys(otherChildren).every(childName => !innerChildren[childName]);
    if (collapsable)
      return {
        data: { type: 'fileDir' },
        children: {
          ...collapseChildrenFixtureDirs(otherChildren, fixturesDir),
          ...innerChildren,
        },
      };
  }

  return {
    ...treeNode,
    children: collapseChildrenFixtureDirs(children, fixturesDir),
  };
}

function collapseChildrenFixtureDirs(
  children: Record<string, FixtureTreeNode>,
  fixturesDir: string
) {
  return Object.keys(children).reduce(
    (newChildren, childName) => ({
      ...newChildren,
      [childName]: collapseFixtureDirs(children[childName], fixturesDir),
    }),
    {}
  );
}
