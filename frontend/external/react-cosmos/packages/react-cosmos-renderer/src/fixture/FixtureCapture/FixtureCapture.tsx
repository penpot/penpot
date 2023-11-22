// In a React Server Components setup this is a Client entry point for fixtures.
// In client fixtures the entire children tree is made out of client components.
// Server fixtures, however, are rendered on the server and returned as JSON
// elements that get passed through this client-side FixtureCapture decorator.
// This results in two types of client-side interaction with server-side props:
// 1. Props of client side components used in server fixtures.
// 2. Props of native elements rendered by server components (eg. div, p, etc.)
'use client';
import React from 'react';
import { FixtureDecoratorId } from 'react-cosmos-core';
import { useClassStateCapture } from './classState/index.js';
import { usePropsCapture } from './props/index.js';

type Props = {
  children: React.ReactNode;
  decoratorId: FixtureDecoratorId;
};

export function FixtureCapture({ children, decoratorId }: Props) {
  let fixture = usePropsCapture(children, decoratorId);
  fixture = useClassStateCapture(fixture, decoratorId);

  // https://github.com/DefinitelyTyped/DefinitelyTyped/issues/18051
  return <>{fixture}</>;
}
