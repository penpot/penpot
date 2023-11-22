import { ComponentType } from 'react';

const componentNames: WeakMap<ComponentType, string> = new WeakMap();

export function getComponentName(type: string | ComponentType): string {
  if (typeof type === 'string') {
    return type;
  }

  if (!componentNames.has(type)) {
    const name = type.displayName || type.name || '';
    componentNames.set(type, name);
  }

  return componentNames.get(type) as string;
}
