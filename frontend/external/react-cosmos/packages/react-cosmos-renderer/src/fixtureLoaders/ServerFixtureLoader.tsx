// https://github.com/DefinitelyTyped/DefinitelyTyped/pull/65220
/// <reference types="react/experimental" />

import React, { Suspense } from 'react';
import { ReactDecorator, UserModuleWrappers } from 'react-cosmos-core';
import { FixtureModule } from '../fixtureModule/FixtureModule.js';
import { AsyncModuleLoader } from '../moduleLoaders/AsyncModuleLoader.js';
import { FixtureLoaderConnect } from './FixtureLoaderConnect.js';
import { SelectedFixture } from './SelectedFixture.js';
import { defaultRenderMessage } from './defaultRenderMessage.js';

// This fixture loader is designed for React Server Components setups.
// Although server components are stateless, this fixture loader still
// communicates with the Cosmos UI through Client components via postMessage
// or WebSocket messages. The main distinction from the client fixture loader
// is that the fixture modules here are loaded on the server. This means that
// this fixture loader cannot respond directly to 'fixtureSelect' client
// requests. Instead, here the fixture is selected from the server-side HTTP
// request search params. Fixture change requests are then received on the
// client, which triggers a page reload by changing the URL's search params,
// which in turn triggers a new fixture selection on the server.
type Props = {
  moduleWrappers: UserModuleWrappers;
  globalDecorators?: ReactDecorator[];
  renderMessage?: (msg: string) => React.ReactElement;
  selectedFixture: SelectedFixture | null;
};
export function ServerFixtureLoader({
  moduleWrappers,
  globalDecorators,
  renderMessage = defaultRenderMessage,
  selectedFixture,
}: Props) {
  return (
    <FixtureLoaderConnect
      moduleWrappers={moduleWrappers}
      selectedFixture={selectedFixture}
      renderMessage={renderMessage}
      renderFixture={selected => (
        // The suspense boundary allows the rendererReady response to be sent
        // before loading the fixture modules.
        <Suspense>
          <AsyncModuleLoader
            moduleWrappers={moduleWrappers}
            fixturePath={selected.fixtureId.path}
            renderModules={modules => (
              <FixtureModule
                {...modules}
                {...selected}
                globalDecorators={globalDecorators}
                lazy={moduleWrappers.lazy}
                renderMessage={renderMessage}
              />
            )}
          />
        </Suspense>
      )}
    />
  );
}
