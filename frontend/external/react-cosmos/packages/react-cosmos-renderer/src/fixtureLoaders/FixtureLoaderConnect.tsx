import React from 'react';
import {
  DelayRender,
  UserModuleWrappers,
  getFixtureListFromWrappers,
} from 'react-cosmos-core';
import { RendererSync } from './RendererSync.js';
import { SelectedFixture } from './SelectedFixture.js';

type Props = {
  moduleWrappers: UserModuleWrappers;
  // Receiving the fixture selection as a prop instead of reading it from the
  // RendererContext enables using this component on the server, in which case
  // the selected fixture is read from server-side URL params.
  selectedFixture: SelectedFixture | null;
  renderMessage: (msg: string) => React.ReactElement;
  renderFixture: (selected: SelectedFixture) => React.ReactElement;
};
export function FixtureLoaderConnect({
  moduleWrappers,
  selectedFixture,
  renderMessage,
  renderFixture,
}: Props) {
  const fixtures = React.useMemo(
    () => getFixtureListFromWrappers(moduleWrappers),
    [moduleWrappers]
  );

  function renderInner() {
    if (!selectedFixture) {
      return (
        <DelayRender delay={500}>
          {renderMessage('No fixture selected.')}
        </DelayRender>
      );
    }

    const { fixtureId } = selectedFixture;
    if (!fixtures[fixtureId.path]) {
      return renderMessage(`Fixture path not found: ${fixtureId.path}`);
    }

    return renderFixture(selectedFixture);
  }

  return <RendererSync fixtures={fixtures}>{renderInner()}</RendererSync>;
}
