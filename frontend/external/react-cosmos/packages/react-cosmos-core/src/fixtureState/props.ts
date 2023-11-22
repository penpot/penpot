import { find, isEqual } from 'lodash-es';
import {
  removeItemMatch,
  replaceOrAddItem,
  updateItem,
} from '../utils/array.js';
import {
  FixtureDecoratorId,
  FixtureElementId,
  FixtureRenderKey,
  FixtureState,
  FixtureStateProps,
  FixtureStateValues,
} from './types.js';

export const DEFAULT_RENDER_KEY: FixtureRenderKey = 0;

export function getFixtureStateProps(
  fixtureState: FixtureState,
  decoratorId: FixtureDecoratorId
): FixtureStateProps[] {
  const { props } = fixtureState;
  return props
    ? props.filter(p => p.elementId.decoratorId === decoratorId)
    : [];
}

export function findFixtureStateProps(
  fixtureState: FixtureState,
  elementId: FixtureElementId
): void | FixtureStateProps {
  const { props } = fixtureState;
  return props && find(props, p => isEqual(p.elementId, elementId));
}

type CreateFixtureStatePropsArgs = {
  fixtureState: FixtureState;
  elementId: FixtureElementId;
  values: FixtureStateValues;
  componentName: string;
};
export function createFixtureStateProps({
  fixtureState,
  elementId,
  values,
  componentName,
}: CreateFixtureStatePropsArgs) {
  const { props = [] } = fixtureState;
  return replaceOrAddItem(props, createPropsMatcher(elementId), {
    elementId,
    values,
    renderKey: DEFAULT_RENDER_KEY,
    componentName,
  });
}

type ResetFixtureStatePropsArgs = {
  fixtureState: FixtureState;
  elementId: FixtureElementId;
  values: FixtureStateValues;
};
export function resetFixtureStateProps({
  fixtureState,
  elementId,
  values,
}: ResetFixtureStatePropsArgs) {
  const propsItem = expectFixtureStateProps(fixtureState, elementId);
  return updateItem(fixtureState.props!, propsItem, {
    values,
    renderKey: propsItem.renderKey + 1,
  });
}

type UpdateFixtureStatePropsArgs = {
  fixtureState: FixtureState;
  elementId: FixtureElementId;
  values: FixtureStateValues;
};
export function updateFixtureStateProps({
  fixtureState,
  elementId,
  values,
}: UpdateFixtureStatePropsArgs) {
  const propsItem = expectFixtureStateProps(fixtureState, elementId);
  return updateItem(fixtureState.props!, propsItem, {
    values,
  });
}

export function removeFixtureStateProps(
  fixtureState: FixtureState,
  elementId: FixtureElementId
) {
  return removeItemMatch(
    fixtureState.props || [],
    createPropsMatcher(elementId)
  );
}

function createPropsMatcher(elementId: FixtureElementId) {
  return (p: FixtureStateProps) => isEqual(p.elementId, elementId);
}

function expectFixtureStateProps(
  fixtureState: FixtureState,
  elementId: FixtureElementId
): FixtureStateProps {
  const propsItem = findFixtureStateProps(fixtureState, elementId);
  if (!propsItem) {
    const elId = JSON.stringify(elementId);
    throw new Error(`Fixture state props missing for element "${elId}"`);
  }
  return propsItem;
}
