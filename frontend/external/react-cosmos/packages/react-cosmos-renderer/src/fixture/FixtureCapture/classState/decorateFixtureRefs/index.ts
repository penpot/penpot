import {
  cloneElement,
  Component,
  MutableRefObject,
  ReactElement,
  ReactNode,
  Ref,
} from 'react';
import { findRelevantElementPaths } from '../../shared/findRelevantElementPaths.js';
import { setElementAtPath } from '../../shared/nodeTree/index.js';
import { CachedRefHandlers } from '../shared.js';
import { isRefSupported } from './isRefSupported.js';

type ElementWithRef = ReactElement & {
  ref: null | Ref<any>;
};

type SpyRef = (elPath: string, elRef: null | Component) => unknown;

export function decorateFixtureRefs(
  fixture: ReactNode,
  spyRef: SpyRef,
  cachedRefHandlers: CachedRefHandlers
) {
  const elPaths = findRelevantElementPaths(fixture);
  return elPaths.reduce((decoratedFixture, elPath): ReactNode => {
    return setElementAtPath(decoratedFixture, elPath, element => {
      if (!isRefSupported(element.type)) {
        return element;
      }

      return cloneElement(element, {
        ref: getDecoratedRef(
          (element as ElementWithRef).ref,
          spyRef,
          elPath,
          cachedRefHandlers
        ),
      });
    });
  }, fixture);
}

function getDecoratedRef(
  origRef: null | Ref<any>,
  spyRef: SpyRef,
  elPath: string,
  cachedRefHandlers: CachedRefHandlers
) {
  const found = cachedRefHandlers[elPath];
  if (found && found.origRef === origRef) {
    return found.handler;
  }

  const handler = decorateRefWithSpy(origRef, spyRef, elPath);
  cachedRefHandlers[elPath] = { origRef, handler };

  return handler;
}

function decorateRefWithSpy(
  origRef: null | Ref<any>,
  spyRef: SpyRef,
  elPath: string
) {
  return (elRef: null | Component) => {
    if (origRef) {
      callOriginalRef(origRef, elRef);
    }
    spyRef(elPath, elRef);
  };
}

function callOriginalRef(ref: Ref<any>, elRef: null | Component) {
  if (typeof ref === 'string') {
    console.warn('[decorateFixtureRefs] String refs are not supported');
    return;
  }

  if (typeof ref === 'function') {
    ref(elRef);
    return;
  }

  const refObj = ref as MutableRefObject<any>;
  refObj.current = elRef;
}
