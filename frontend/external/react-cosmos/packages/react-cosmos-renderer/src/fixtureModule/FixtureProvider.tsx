'use client';
import { isEqual } from 'lodash-es';
import React from 'react';
import {
  FixtureId,
  FixtureListItem,
  FixtureState,
  SetFixtureState,
} from 'react-cosmos-core';
import { FixtureContext } from '../fixture/FixtureContext.js';
import { RendererContext } from '../rendererConnect/RendererContext.js';

type Props = {
  children: React.ReactNode;
  fixtureId: FixtureId;
  initialFixtureState?: FixtureState;
  fixtureItem: FixtureListItem;
  lazy: boolean;
};

type State = {
  fixtureState: FixtureState;
  syncedFixtureState: FixtureState;
};

export function FixtureProvider(props: Props) {
  const [state, setState] = React.useState<State>({
    fixtureState: props.initialFixtureState || {},
    syncedFixtureState: {},
  });

  const { rendererId, rendererConnect, setLazyItems } =
    React.useContext(RendererContext);

  React.useEffect(() => {
    // Only multi fixtures have extra info that isn't already available in the
    // fixture list provided to the Cosmos UI (fixture names, which in lazy mode
    // are revealed after importing a fixture module).
    if (props.lazy) {
      setLazyItems(
        props.fixtureItem.type === 'multi'
          ? { [props.fixtureId.path]: props.fixtureItem }
          : noLazyItem
      );
    }
  }, [props.fixtureId.path, props.fixtureItem, props.lazy, setLazyItems]);

  React.useEffect(() => {
    if (!isEqual(state.fixtureState, state.syncedFixtureState)) {
      rendererConnect.postMessage({
        type: 'fixtureStateChange',
        payload: {
          rendererId,
          fixtureId: props.fixtureId,
          fixtureState: state.fixtureState,
        },
      });
      setState(prevState => ({
        ...prevState,
        syncedFixtureState: state.fixtureState,
      }));
    }
  }, [
    props.fixtureId,
    rendererConnect,
    rendererId,
    state.fixtureState,
    state.syncedFixtureState,
  ]);

  React.useEffect(
    () =>
      rendererConnect.onMessage(msg => {
        if (
          msg.type === 'setFixtureState' &&
          msg.payload.rendererId === rendererId
        ) {
          const { fixtureId, fixtureState } = msg.payload;
          setState(prevState =>
            // Ensure fixture state applies to currently selected fixture
            isEqual(fixtureId, props.fixtureId)
              ? { ...prevState, fixtureState, syncedFixtureState: fixtureState }
              : prevState
          );
        }
      }),
    [props.fixtureId, rendererConnect, rendererId]
  );

  const setFixtureState = React.useCallback<SetFixtureState>(
    stateUpdate => {
      setState(prevState => ({
        ...prevState,
        fixtureState: stateUpdate(prevState.fixtureState),
      }));
    },
    [setState]
  );

  const contextValue = React.useMemo(
    () => ({ fixtureState: state.fixtureState, setFixtureState }),
    [setFixtureState, state.fixtureState]
  );

  return (
    <FixtureContext.Provider value={contextValue}>
      {props.children}
    </FixtureContext.Provider>
  );
}

const noLazyItem = {};
