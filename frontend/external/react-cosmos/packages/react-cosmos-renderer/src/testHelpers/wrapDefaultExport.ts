import { mapValues } from 'lodash-es';
import { ByPath } from 'react-cosmos-core';

export function wrapDefaultExport<T>(modules: ByPath<T>) {
  return mapValues(modules, defaultExport => ({ default: defaultExport }));
}
