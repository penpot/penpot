import { isEqual, isEqualWith } from 'lodash-es';
import { ComponentType, ReactElement, ReactNode } from 'react';
import { getComponentName } from './getComponentName.js';
import { isReactElement } from './isReactElement.js';

export function areNodesEqual(
  node1: ReactNode,
  node2: ReactNode,
  strictTypeCheck: boolean
): boolean {
  if (isReactElement(node1) && isReactElement(node2))
    return areElementsEqual(node1, node2, strictTypeCheck);

  if (Array.isArray(node1) && Array.isArray(node2))
    return areArrayNodesEqual(node1, node2, strictTypeCheck);

  return isEqual(node1, node2);
}

function areElementsEqual(
  element1: ReactElement,
  element2: ReactElement,
  strictTypeCheck: boolean
) {
  if (!areElementTypesEqual(element1.type, element2.type, strictTypeCheck))
    return false;

  // Don't compare private element attrs like _owner and _store, which hold
  // internal details and have auto increment-type attrs
  return (
    element1.key === element2.key &&
    // @ts-ignore
    element1.ref === element2.ref &&
    // Children (and props in general) can contain Elements and other Nodes
    arePropsEqual(element1.props, element2.props)
  );
}

function areElementTypesEqual(
  type1: string | ComponentType,
  type2: string | ComponentType,
  strictTypeCheck: boolean
) {
  return strictTypeCheck
    ? type1 === type2
    : getComponentName(type1) === getComponentName(type2);
}

function areArrayNodesEqual(
  node1: ReactNode[],
  node2: ReactNode[],
  strictTypeCheck: boolean
) {
  if (node1.length !== node2.length) return false;

  return node1.every((node, nodeIndex) =>
    areNodesEqual(node, node2[nodeIndex], strictTypeCheck)
  );
}

type PlainObject = Record<string, unknown>;

function arePropsEqual(object1: PlainObject, object2: PlainObject) {
  if (!isEqual(Object.keys(object1), Object.keys(object2))) return false;

  return Object.keys(object1).every(key =>
    isEqualWith(
      object1[key],
      object2[key],
      (value1: unknown, value2: unknown) =>
        typeof value1 === 'function' && typeof value2 === 'function'
          ? value1 === value2 || value1.toString() === value2.toString()
          : isEqual(value1, value2)
    )
  );
}
