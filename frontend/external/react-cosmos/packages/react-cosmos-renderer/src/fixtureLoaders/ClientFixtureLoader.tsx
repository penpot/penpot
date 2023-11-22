'use client';
import React, { ReactElement } from 'react';
import {
  FixtureModules,
  ReactDecorator,
  UserModuleWrappers,
} from 'react-cosmos-core';
import { FixtureModule } from '../fixtureModule/FixtureModule.js';
import { LazyModuleLoader } from '../moduleLoaders/LazyModuleLoader.js';
import { StaticModuleLoader } from '../moduleLoaders/StaticModuleLoader.js';
import { RendererContext } from '../rendererConnect/RendererContext.js';
import { FixtureLoaderConnect } from './FixtureLoaderConnect.js';
import { defaultRenderMessage } from './defaultRenderMessage.js';

type Props = {
  moduleWrappers: UserModuleWrappers;
  globalDecorators?: ReactDecorator[];
  renderMessage?: (msg: string) => ReactElement;
};
export function ClientFixtureLoader({
  moduleWrappers,
  globalDecorators,
  renderMessage = defaultRenderMessage,
}: Props) {
  const { selectedFixture } = React.useContext(RendererContext);
  return (
    <FixtureLoaderConnect
      moduleWrappers={moduleWrappers}
      selectedFixture={selectedFixture}
      renderMessage={renderMessage}
      renderFixture={selected => {
        function renderModules(modules: FixtureModules) {
          return (
            <FixtureModule
              {...modules}
              {...selected}
              globalDecorators={globalDecorators}
              lazy={moduleWrappers.lazy}
              renderMessage={renderMessage}
            />
          );
        }

        const { fixtureId } = selected;
        return moduleWrappers.lazy ? (
          <LazyModuleLoader
            fixtureWrapper={moduleWrappers.fixtures[fixtureId.path]}
            decorators={moduleWrappers.decorators}
            fixturePath={fixtureId.path}
            renderModules={renderModules}
          />
        ) : (
          <StaticModuleLoader
            fixtureWrapper={moduleWrappers.fixtures[fixtureId.path]}
            decorators={moduleWrappers.decorators}
            fixturePath={fixtureId.path}
            renderModules={renderModules}
          />
        );
      }}
    />
  );
}
