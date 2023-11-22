import { SetSelectValue, UseSelectArgs } from './shared.js';
import { useCreateFixtureState } from './useCreateFixtureState.js';
import { useCurrentValue } from './useCurrentValue.js';
import { useSetValue } from './useSetValue.js';

export function useSelect<Option extends string>(
  selectName: string,
  args: UseSelectArgs<Option>
): [Option, SetSelectValue<Option>] {
  if (!args || !args.options || !args.options.length)
    throw new Error('No options provided to useSelect');

  useCreateFixtureState(selectName, args);
  const currentValue = useCurrentValue(selectName, args);
  const setValue = useSetValue<Option>(selectName);

  return [currentValue, setValue];
}
