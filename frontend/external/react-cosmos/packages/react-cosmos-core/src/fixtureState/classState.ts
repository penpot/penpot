import { find, isEqual } from 'lodash-es';
import {
  removeItemMatch,
  replaceOrAddItem,
  updateItem,
} from '../utils/array.js';
import {
  FixtureDecoratorId,
  FixtureElementId,
  FixtureState,
  FixtureStateClassState,
  FixtureStateValues,
} from './types.js';

export function getFixtureStateClassState(
  fixtureState: FixtureState,
  decoratorId: FixtureDecoratorId
): FixtureStateClassState[] {
  const { classState } = fixtureState;
  return classState
    ? classState.filter(s => s.elementId.decoratorId === decoratorId)
    : [];
}

export function findFixtureStateClassState(
  fixtureState: FixtureState,
  elementId: FixtureElementId
): void | FixtureStateClassState {
  const { classState } = fixtureState;
  return classState && find(classState, s => isEqual(s.elementId, elementId));
}

type CreateFixtureStateClassStateArgs = {
  fixtureState: FixtureState;
  elementId: FixtureElementId;
  values: FixtureStateValues;
  componentName: string;
};
export function createFixtureStateClassState({
  fixtureState,
  elementId,
  values,
  componentName,
}: CreateFixtureStateClassStateArgs) {
  const { classState = [] } = fixtureState;
  return replaceOrAddItem(classState, createClassStateMatcher(elementId), {
    elementId,
    values,
    componentName,
  });
}

type UpdateFixtureStateClassStateArgs = {
  fixtureState: FixtureState;
  elementId: FixtureElementId;
  values: FixtureStateValues;
};
export function updateFixtureStateClassState({
  fixtureState,
  elementId,
  values,
}: UpdateFixtureStateClassStateArgs) {
  const classStateItem = expectFixtureStateClassState(fixtureState, elementId);
  return updateItem(fixtureState.classState!, classStateItem, {
    values,
  });
}

export function removeFixtureStateClassState(
  fixtureState: FixtureState,
  elementId: FixtureElementId
) {
  return removeItemMatch(
    fixtureState.classState || [],
    createClassStateMatcher(elementId)
  );
}

function createClassStateMatcher(elementId: FixtureElementId) {
  return (p: FixtureStateClassState) => isEqual(p.elementId, elementId);
}

function expectFixtureStateClassState(
  fixtureState: FixtureState,
  elementId: FixtureElementId
): FixtureStateClassState {
  const classStateItem = findFixtureStateClassState(fixtureState, elementId);
  if (!classStateItem) {
    const elId = JSON.stringify(elementId);
    throw new Error(`Fixture state class state missing for element "${elId}"`);
  }
  return classStateItem;
}
