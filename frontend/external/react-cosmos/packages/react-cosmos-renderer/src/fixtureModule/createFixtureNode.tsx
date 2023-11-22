import React from 'react';
import { ReactFixture } from 'react-cosmos-core';

export function createFixtureNode(fixture: ReactFixture): React.ReactNode {
  // Warning: In a React Server Components setup this function is called on the
  // server. When a fixture module uses the 'use client' directive, the fixture
  // arg received here will be a function wrapper regardless of the fixture
  // module contents. In this scenario, the fixture module will be automatically
  // rendered on the client side, where React expects a component default export.
  // This results in the following limitation: In a React Server Components
  // setup, Client fixtures have to export a single function component. They
  // can't be multi fixtures and they can't export React elements directly.
  return isFunctionFixture(fixture) ? (
    <FixtureElement Component={fixture} />
  ) : (
    fixture
  );
}

function isFunctionFixture(
  fixture: ReactFixture
): fixture is React.FunctionComponent {
  return typeof fixture === 'function';
}

function FixtureElement({ Component }: { Component: React.FunctionComponent }) {
  return <Component />;
}
FixtureElement.cosmosCapture = false;
