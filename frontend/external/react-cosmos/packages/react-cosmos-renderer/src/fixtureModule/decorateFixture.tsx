import React from 'react';
import { ReactDecorator } from 'react-cosmos-core';

export function decorateFixture(
  fixtureNode: React.ReactNode,
  decorators: ReactDecorator[]
): React.ReactElement {
  return (
    <>
      {[...decorators].reverse().reduce(
        (prevElement, Decorator) => (
          <Decorator>{prevElement}</Decorator>
        ),
        fixtureNode
      )}
    </>
  );
}
