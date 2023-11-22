import { set } from 'lodash-es';
import { ReactElement, ReactNode } from 'react';
import { isReactElement } from 'react-cosmos-core';
import { getExpectedElementAtPath } from './getElementAtPath.js';
import { isRootPath } from './shared.js';

export function setElementAtPath(
  node: ReactNode,
  elPath: string,
  updater: (el: ReactElement) => ReactElement
): ReactNode {
  const childEl = getExpectedElementAtPath(node, elPath);
  const newEl = updater(childEl);

  if (isRootPath(elPath)) {
    return newEl;
  }

  // If the root is a non-Array non-Element Node we should be at the root path
  // and returned already
  const clonedRoot = cloneNode(node) as ReactElement | ReactNode[];

  // _.set also accepts arrays
  // https://github.com/lodash/lodash/blob/6018350ac10d5ce6a5b7db625140b82aeab804df/isObject.js#L15-L16
  return set(clonedRoot, elPath, newEl);
}

function cloneNode(value: ReactNode) {
  return Array.isArray(value)
    ? value.map(n => cloneNodeItem(n))
    : cloneNodeItem(value);
}

function cloneNodeItem(value: ReactNode) {
  return isReactElement(value) ? cloneReactElement(value) : value;
}

function cloneReactElement(value: ReactElement): ReactElement {
  const { children, ...otherProps } = value.props;
  return {
    ...value,
    props: {
      ...otherProps,
      children: cloneNode(children as ReactNode),
    },
  };
}
