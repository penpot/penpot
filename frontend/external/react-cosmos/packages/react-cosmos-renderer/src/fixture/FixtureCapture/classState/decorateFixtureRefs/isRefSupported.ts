import React from 'react';
import { ForwardRef } from 'react-is';

type ElementType = string | React.ComponentType;

type ExtendedComponentType = React.ComponentType & {
  $$typeof: any;
};

export function isRefSupported(elementType: ElementType): boolean {
  if (typeof elementType === 'string') {
    return false;
  }

  const { $$typeof, prototype } = elementType as ExtendedComponentType;
  return (
    $$typeof === ForwardRef ||
    // Warning: Some functions don't have the .prototype property
    (prototype &&
      // ES6 Class
      // Warning: This will return false is the component is extending a
      // different copy of React than the one used by Cosmos. This is relevant
      // when running Cosmos from an external location instead of node_modules.
      (prototype instanceof React.Component ||
        // React.createClass
        prototype.getInitialState !== undefined) &&
      true)
  );
}
