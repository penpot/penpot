import { FixtureTreeNode } from '../types.js';

export function collapseNamedIndexes(
  treeNode: FixtureTreeNode
): FixtureTreeNode {
  const { data, children } = treeNode;
  if (data.type !== 'fileDir' || !children) return treeNode;

  return {
    ...treeNode,
    children: Object.keys(children).reduce((newChildren, childName) => {
      const childNode = children[childName];
      const next = () => ({
        ...newChildren,
        [childName]: collapseNamedIndexes(childNode),
      });

      const grandchildren = childNode.children;
      if (childNode.data.type !== 'fileDir' || !grandchildren) return next();

      const grandchildNames = Object.keys(grandchildren);
      if (grandchildNames.length !== 1) return next();

      const [firstGrandchildName] = grandchildNames;
      const firstGrandchildNode = grandchildren[firstGrandchildName];
      if (
        firstGrandchildNode.data.type !== 'fileDir' &&
        noCaseEqual(childName, firstGrandchildName)
      )
        return { ...newChildren, [firstGrandchildName]: firstGrandchildNode };

      return next();
    }, {}),
  };
}

function noCaseEqual(a: string, b: string) {
  return a.toUpperCase() === b.toUpperCase();
}
