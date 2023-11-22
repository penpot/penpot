import React, { useEffect } from 'react';
import { findFixtureStateControl } from 'react-cosmos-core';
import { FixtureContext } from '../FixtureContext.js';
import { getDefaultSelectValue, UseSelectArgs } from './shared.js';

export function useCreateFixtureState<Option extends string>(
  selectName: string,
  args: UseSelectArgs<Option>
) {
  const { setFixtureState } = React.useContext(FixtureContext);
  const defaultValue = getDefaultSelectValue(args);
  useEffect(() => {
    // The fixture state for this select is (re)created in two situations:
    // 1. Initially: No corresponding fixture state select is found
    // 2: Default value change: Current value is reset to new default value
    setFixtureState(prevFs => {
      const fsControl = findFixtureStateControl(prevFs, selectName);
      if (
        fsControl &&
        fsControl.type === 'select' &&
        fsControl.defaultValue === defaultValue
      )
        return prevFs;

      return {
        ...prevFs,
        controls: {
          ...prevFs.controls,
          [selectName]: {
            type: 'select',
            options: args.options,
            defaultValue,
            currentValue: defaultValue,
          },
        },
      };
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [String(args.options), defaultValue, selectName, setFixtureState]);
}
