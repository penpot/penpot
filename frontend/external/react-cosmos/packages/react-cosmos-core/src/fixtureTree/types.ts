import { TreeNode } from '../utils/tree.js';

export type FixtureTreeNode = TreeNode<
  | { type: 'fileDir' }
  | { type: 'fixture'; path: string }
  | { type: 'multiFixture'; path: string; names: string[] }
>;
