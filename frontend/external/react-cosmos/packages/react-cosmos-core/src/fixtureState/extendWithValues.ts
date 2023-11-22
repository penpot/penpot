import { isArray, isObject } from './shared.js';
import { FixtureStateValue, FixtureStateValues, ObjectData } from './types.js';

// Use fixture state for serializable values and fall back to base values
export function extendWithValues(
  obj: ObjectData,
  values: FixtureStateValues
): ObjectData {
  const extendedObj: ObjectData = {};
  Object.keys(values).forEach(key => {
    extendedObj[key] = extendWithValue(obj[key], values[key]);
  });
  return extendedObj;
}

export function extendWithValue(
  data: unknown,
  value: FixtureStateValue
): unknown {
  if (value.type === 'unserializable') return data;

  if (value.type === 'object') {
    const obj = isObject(data) ? data : {};
    return extendWithValues(obj, value.values);
  }

  if (value.type === 'array') {
    const array = isArray(data) ? data : [];
    return value.values.map((v, idx) => extendWithValue(array[idx], v));
  }

  return value.data;
}
