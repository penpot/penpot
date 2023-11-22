import { isEqual } from 'lodash-es';
import React from 'react';
import {
  FixtureStateData,
  FixtureStateValue,
  createValue,
  extendWithValue,
  findFixtureStateControl,
} from 'react-cosmos-core';
import { FixtureContext } from '../FixtureContext.js';

export function useCreateFixtureState(
  inputName: string,
  defaultValue: FixtureStateData
) {
  const { setFixtureState } = React.useContext(FixtureContext);
  React.useEffect(() => {
    // The fixture state for this value is (re)created in two situations:
    // 1. Initially: No corresponding fixture state value is found
    // 2: Default value change: Current value is reset to new default value
    setFixtureState(prevFs => {
      const fsControl = findFixtureStateControl(prevFs, inputName);
      if (
        fsControl &&
        fsControl.type === 'standard' &&
        fsValueExtendsBaseValue(fsControl.defaultValue, defaultValue)
      )
        return prevFs;

      return {
        ...prevFs,
        controls: {
          ...prevFs.controls,
          [inputName]: {
            type: 'standard',
            defaultValue: createValue(defaultValue),
            currentValue: createValue(defaultValue),
          },
        },
      };
    });
  }, [setFixtureState, inputName, defaultValue]);
}

function fsValueExtendsBaseValue(
  fsValue: FixtureStateValue,
  baseValue: unknown
) {
  return isEqual(baseValue, extendWithValue(baseValue, fsValue));
}
