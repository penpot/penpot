import { isElement } from 'react-is';
import { isArray, isObject, isPrimitiveData } from './shared.js';
import { FixtureStateValue, FixtureStateValues, ObjectData } from './types.js';

export function createValues(obj: ObjectData): FixtureStateValues {
  const values: FixtureStateValues = {};
  Object.keys(obj).forEach(key => {
    values[key] = createValue(obj[key]);
  });
  return values;
}

export function createValue(data: unknown): FixtureStateValue {
  if (isPrimitiveData(data)) return { type: 'primitive', data };

  if (isArray(data))
    return {
      type: 'array',
      values: (data as unknown[]).map(i => createValue(i)),
    };

  if (isObject(data))
    return {
      type: 'object',
      values: createValues(data),
    };

  // Why store unserializable values in fixture state?
  // - Because they still provides value in the Cosmos UI. They let the user know
  //   that, eg. a prop, is present and see the read-only stringified value.
  // - More importantly, because the fixture state controls which props to render.
  //   This way, if a prop is read-only and cannot be edited in the UI, it can
  //   still be removed.
  return {
    type: 'unserializable',
    stringifiedData: stringifyUnserializableData(data),
  };
}

function stringifyUnserializableData(data: unknown) {
  // NOTE: We used to use the react-element-to-jsx-string package but it added
  // bloat and complicated ESM support. We might go back to something similar
  // in the future. Or expose a plugin API for custom stringifiers.
  return isElement(data) ? '<React.Element />' : String(data);
}
