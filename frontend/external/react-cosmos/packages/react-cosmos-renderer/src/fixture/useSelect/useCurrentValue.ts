import { useContext } from 'react';
import { findFixtureStateControl } from 'react-cosmos-core';
import { FixtureContext } from '../FixtureContext.js';
import { getDefaultSelectValue, UseSelectArgs } from './shared.js';

export function useCurrentValue<Option extends string>(
  selectName: string,
  args: UseSelectArgs<Option>
): Option {
  const { fixtureState } = useContext(FixtureContext);
  const fsControl = findFixtureStateControl(fixtureState, selectName);
  return fsControl && fsControl.type === 'select'
    ? (fsControl.currentValue as Option)
    : getDefaultSelectValue(args);
}
