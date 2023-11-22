import { isEqual } from 'lodash-es';
import {
  Component,
  ComponentClass,
  ComponentType,
  MutableRefObject,
  ReactNode,
  useContext,
  useEffect,
  useRef,
} from 'react';
import {
  FixtureDecoratorId,
  FixtureState,
  createFixtureStateClassState,
  createValues,
  extendWithValues,
  findFixtureStateClassState,
  getComponentName,
  getFixtureStateClassState,
  removeFixtureStateClassState,
} from 'react-cosmos-core';
import { FixtureContext } from '../../FixtureContext.js';
import { findRelevantElementPaths } from '../shared/findRelevantElementPaths.js';
import { decorateFixtureRefs } from './decorateFixtureRefs/index.js';
import {
  CachedRefHandlers,
  ElRefs,
  InitialStates,
  replaceState,
} from './shared.js';

export function useFixtureState(
  fixture: ReactNode,
  decoratorId: FixtureDecoratorId,
  elRefs: MutableRefObject<ElRefs>
) {
  const elPaths = findRelevantElementPaths(fixture);
  const { fixtureState, setFixtureState } = useContext(FixtureContext);
  const lastFixtureState = useFixtureStateRef(fixtureState);
  // Keep a copy of the previous fixture state to observe changes
  const prevFixtureState = useRef(fixtureState);
  // Remember initial state of child components to use as a default when
  // resetting fixture state
  const initialStates = useRef<InitialStates>({});
  // Ref handlers are reused because every time we pass a new ref handler to
  // a React element it gets called in the next render loop, even when the
  // associated element instance has been preserved. Having ref handlers fire
  // on every render loop results in unwanted operations and race conditions.
  const cachedRefHandlers = useRef<CachedRefHandlers>({});

  useEffect(() => {
    return () => {
      initialStates.current = {};
      cachedRefHandlers.current = {};
    };
  }, []);

  useEffect(() => {
    // Remove fixture state for removed child elements (likely via HMR)
    // FIXME: Also invalidate fixture state at this element path if the
    // component type of the corresponding element changed
    const fsProps = getFixtureStateClassState(fixtureState, decoratorId);
    fsProps.forEach(({ elementId }) => {
      const { elPath } = elementId;
      if (elPaths.indexOf(elementId.elPath) === -1) {
        setFixtureState(prevFs => ({
          ...prevFs,
          classState: removeFixtureStateClassState(fixtureState, elementId),
        }));
        if (elRefs.current[elPath]) {
          delete elRefs.current[elPath];
          delete initialStates.current[elPath];
          delete cachedRefHandlers.current[elPath];
        }
      }
    });

    elPaths.forEach(elPath => {
      const elementId = { decoratorId, elPath };
      // Component fixture state can be provided before the fixture mounts (eg.
      // a previous snapshot of a fixture state or the current fixture state
      // from another renderer)
      const fsClassState = findFixtureStateClassState(fixtureState, elementId);
      if (!fsClassState) {
        if (initialStates.current[elPath]) {
          const { state } = initialStates.current[elPath];
          const elRef = elRefs.current[elPath];

          if (!isEqual(elRef.state, state)) {
            replaceState(elRef, state);
          }

          setFixtureState(prevFs => ({
            ...prevFs,
            classState: createFixtureStateClassState({
              fixtureState: prevFs,
              elementId,
              values: createValues(state),
              componentName: getComponentName(
                elRef.constructor as ComponentType
              ),
            }),
          }));
        }
      } else {
        const elRef = elRefs.current[elPath];
        // The el ref can be missing for three reasons:
        //   1. Element type is stateless
        //   2. Element type is a class, but doesn't have state. An instance exists
        //      but has been discarded because of its lack of state.
        //   3. Element instance unmounted and is about to remount. When this
        //      happens, the new instance will be handled when its ref fires again.
        if (!elRef) {
          return;
        }

        // The child's state can be out of sync with the fixture state for two
        // reasons:
        //   1. The child's state changed internally
        //   2. The fixture state changed
        // Here we're interested in the second scenario. In the first scenario
        // we want to let the component state override the fixture state.
        const prevFsClassState = findFixtureStateClassState(
          prevFixtureState.current,
          elementId
        );
        if (prevFsClassState && !isEqual(prevFsClassState, fsClassState)) {
          return replaceState(
            elRef,
            extendWithValues(elRef.state, fsClassState.values)
          );
        }
      }
    });
  }, [
    decoratorId,
    elPaths,
    elRefs,
    fixtureState,
    fixtureState.classState,
    setFixtureState,
  ]);

  // Update prev fixture state ref *after* running effects that reference it
  useEffect(() => {
    prevFixtureState.current = fixtureState;
  });

  return decorateFixtureRefs(fixture, handleRef, cachedRefHandlers.current);

  function handleRef(elPath: string, elRef: null | Component) {
    if (!elRef) {
      delete elRefs.current[elPath];
      return;
    }

    // Only track instances with state
    const { state } = elRef;
    if (!state) {
      return;
    }

    elRefs.current[elPath] = elRef;
    setInitialState(initialStates.current, elPath, elRef);

    const elementId = { decoratorId, elPath };
    const fsClassState = findFixtureStateClassState(
      lastFixtureState.current,
      elementId
    );
    if (!fsClassState) {
      setFixtureState(prevFs => ({
        ...prevFs,
        classState: createFixtureStateClassState({
          fixtureState: prevFs,
          elementId,
          values: createValues(state),
          componentName: getComponentName(elRef.constructor as ComponentType),
        }),
      }));
    } else {
      replaceState(elRef, extendWithValues(state, fsClassState.values));
    }
  }
}

// Make latest fixture state accessible in ref callback
function useFixtureStateRef(fixtureState: FixtureState) {
  const ref = useRef(fixtureState);
  useEffect(() => {
    ref.current = fixtureState;
  });
  return ref;
}

function setInitialState(
  initialStates: InitialStates,
  elPath: string,
  elRef: Component
) {
  const found = initialStates[elPath];
  const type = elRef.constructor as ComponentClass;

  // Keep the first state recevied for this type
  const initialStateExists = found && found.type === type;
  if (!initialStateExists && elRef.state) {
    initialStates[elPath] = { type, state: elRef.state };
  }
}
