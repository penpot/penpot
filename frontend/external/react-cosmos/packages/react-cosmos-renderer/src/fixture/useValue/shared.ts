import React from 'react';
import { FixtureStateData } from 'react-cosmos-core';

export type SetValue<T extends FixtureStateData> = React.Dispatch<
  React.SetStateAction<T>
>;
