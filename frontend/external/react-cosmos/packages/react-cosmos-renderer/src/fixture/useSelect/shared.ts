export type UseSelectArgs<Option extends string> = {
  options: Option[];
  defaultValue?: Option;
};

export type SetSelectValue<Option extends string> = (value: Option) => void;

export function getDefaultSelectValue<Option extends string>({
  options,
  defaultValue,
}: UseSelectArgs<Option>): Option {
  return typeof defaultValue === 'string' ? defaultValue : options[0];
}
