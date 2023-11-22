'use client';
import React from 'react';
import { FixtureList } from 'react-cosmos-core';
import { RendererContext } from '../rendererConnect/RendererContext.js';

type Props = {
  children: React.ReactNode;
  fixtures: FixtureList;
};
export function RendererSync({ children, fixtures }: Props) {
  const {
    rendererId,
    rendererConnect,
    locked,
    selectedFixture,
    selectFixture,
    unselectFixture,
    reloadRenderer,
    lazyItems,
  } = React.useContext(RendererContext);

  const selectedFixtureId = selectedFixture?.fixtureId;

  const readyRef = React.useRef(false);
  React.useEffect(() => {
    if (!readyRef.current) {
      rendererConnect.postMessage({
        type: 'rendererReady',
        payload: {
          rendererId,
          selectedFixtureId,
        },
      });
      readyRef.current = true;
    }
  }, [rendererConnect, rendererId, selectedFixtureId]);

  React.useEffect(() => {
    rendererConnect.postMessage({
      type: 'fixtureListUpdate',
      payload: {
        rendererId,
        fixtures: { ...fixtures, ...lazyItems },
      },
    });
  }, [fixtures, lazyItems, rendererConnect, rendererId]);

  React.useEffect(
    () =>
      rendererConnect.onMessage(msg => {
        if (msg.type === 'pingRenderers') {
          rendererConnect.postMessage({
            type: 'rendererReady',
            payload: {
              rendererId,
              selectedFixtureId,
            },
          });
          rendererConnect.postMessage({
            type: 'fixtureListUpdate',
            payload: {
              rendererId,
              fixtures,
            },
          });
        } else if (
          msg.type === 'reloadRenderer' &&
          msg.payload.rendererId === rendererId
        ) {
          reloadRenderer();
        }
      }),
    [fixtures, reloadRenderer, rendererConnect, rendererId, selectedFixtureId]
  );

  React.useEffect(
    () =>
      rendererConnect.onMessage(msg => {
        if (
          !locked &&
          msg.type === 'selectFixture' &&
          msg.payload.rendererId === rendererId
        ) {
          const { fixtureId, fixtureState } = msg.payload;
          selectFixture(fixtureId, fixtureState);
        } else if (
          !locked &&
          msg.type === 'unselectFixture' &&
          msg.payload.rendererId === rendererId
        ) {
          unselectFixture();
        }
      }),
    [locked, rendererConnect, rendererId, selectFixture, unselectFixture]
  );

  return <>{children}</>;
}
