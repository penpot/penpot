import { flatten } from 'lodash-es';
import React, { Fragment } from 'react';
import { isReactElement } from 'react-cosmos-core';
import { getChildrenPath } from './shared.js';

export function findElementPaths(
  node: React.ReactNode,
  curPath: string = ''
): string[] {
  if (Array.isArray(node)) {
    return flatten(
      node.map((child, idx) => findElementPaths(child, `${curPath}[${idx}]`))
    );
  }

  if (!isReactElement(node)) {
    // At this point the node can be null, boolean, string, number, Portal, etc.
    // https://github.com/facebook/flow/blob/172d28f542f49bbc1e765131c9dfb9e31780f3a2/lib/react.js#L13-L20
    return [];
  }

  const { children } = node.props;
  const childElPaths =
    // Props of elements returned by render functions can't be read here
    typeof children !== 'function'
      ? findElementPaths(children, getChildrenPath(curPath))
      : [];

  // Ignore Fragment elements, but include their children
  return node.type === Fragment ? childElPaths : [curPath, ...childElPaths];
}
