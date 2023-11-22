import { isEqual } from 'lodash-es';
import React from 'react';

type Props = {
  children: React.ClassicElement<unknown>;
  // `state` prop must be an object, as does React component state.
  // "The state is user-defined, and it should be a plain JavaScript object."
  // https://reactjs.org/docs/react-component.html#state
  state?: {};
};

// Compied from https://github.com/skidding/react-mock/blob/c33dfa1d6f0c9ce7b3eaba073618d61731a0e82e/packages/state/src/index.js
export class ClassStateMock extends React.Component<Props> {
  static cosmosCapture = false;

  childRef: React.Component | null = null;

  render() {
    const { children } = this.props;

    if (Array.isArray(children)) {
      throw new Error('ClassStateMock only accepts a single child element');
    }

    return React.cloneElement(children, { ref: this.handleRef });
  }

  componentDidUpdate(prevProps: Props) {
    const { childRef } = this;
    const { state } = this.props;

    if (!childRef) {
      throw new Error('childRef missing in ClassStateMock.componentDidUpdate');
    }

    if (state && !isEqual(state, prevProps.state)) {
      replaceState(childRef, state);
    }
  }

  handleRef = (childRef: React.Component | null) => {
    const prevRef = this.props.children.ref;

    this.childRef = childRef;

    if (!childRef) {
      if (prevRef) manuallyCallRef(prevRef, childRef);

      // Nothing else to do on the unmount branch (when refs are set to NULL)
      return;
    }

    if (this.props.state) {
      // Wait until state has been set to call prev ref. This will give the
      // impression that the mocked state is the initial state.
      replaceState(childRef, this.props.state, () => {
        manuallyCallRef(prevRef, childRef);
      });
    } else {
      manuallyCallRef(prevRef, childRef);
    }
  };
}

function replaceState(childRef: React.Component, state: {}, cb?: () => void) {
  // We need to unset existing state keys because React doesn't provide a
  // replaceState method (anymore)
  // https://reactjs.org/docs/react-component.html#setstate
  const nextState = resetOriginalKeys(childRef.state, state);

  if (!isEqual(nextState, childRef.state)) {
    childRef.setState(nextState, cb);
  }
}

function resetOriginalKeys(original: {}, current: {}) {
  const { keys } = Object;

  return keys(original).reduce(
    (result, key) =>
      keys(result).indexOf(key) === -1
        ? { ...result, [key]: undefined }
        : result,
    current
  );
}

function manuallyCallRef(
  ref: React.LegacyRef<unknown> | undefined,
  elRef: React.Component | null
) {
  if (typeof ref === 'string') {
    throw new Error('StateMock does not support string refs');
  }

  // https://reactjs.org/docs/refs-and-the-dom.html#creating-refs
  if (typeof ref === 'function') {
    ref(elRef);
  } else if (ref && typeof ref === 'object') {
    // @ts-ignore
    ref.current = elRef;
  }
}
