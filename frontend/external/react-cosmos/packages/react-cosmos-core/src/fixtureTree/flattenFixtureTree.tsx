import { FixtureId } from '../userModules/fixtureTypes.js';
import { FixtureTreeNode } from './types.js';

export type FlatFixtureTreeItem = {
  fileName: string;
  fixtureId: FixtureId;
  name: string | null;
  parents: string[];
};
export type FlatFixtureTree = FlatFixtureTreeItem[];

export function flattenFixtureTree(
  treeNode: FixtureTreeNode,
  parents: string[] = []
): FlatFixtureTree {
  const { data, children } = treeNode;
  if (data.type === 'fixture' || !children) return [];

  const flatFixtureTree: FlatFixtureTree = [];
  if (children)
    Object.keys(children).forEach(childName => {
      const childNode = children[childName];
      const { data: childData } = childNode;

      if (childData.type === 'fileDir')
        flatFixtureTree.push(
          ...flattenFixtureTree(childNode, [...parents, childName])
        );

      if (childData.type === 'multiFixture')
        childData.names.forEach(fixtureName =>
          flatFixtureTree.push({
            fileName: childName,
            fixtureId: { path: childData.path, name: fixtureName },
            parents,
            name: fixtureName,
          })
        );

      if (childData.type === 'fixture')
        flatFixtureTree.push({
          fileName: childName,
          fixtureId: { path: childData.path },
          parents,
          name: null,
        });
    });

  return flatFixtureTree;
}
