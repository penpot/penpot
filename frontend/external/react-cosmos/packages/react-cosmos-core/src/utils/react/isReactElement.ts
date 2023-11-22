import { ReactElement, ReactNode } from 'react';
import { isElement } from 'react-is';

export function isReactElement(node: ReactNode): node is ReactElement {
  return isElement(node);
}
