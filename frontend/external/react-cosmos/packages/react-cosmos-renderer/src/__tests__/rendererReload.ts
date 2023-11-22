import retry from '@skidding/async-retry';
import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const onReloadRenderer = jest.fn();
const fixtures = wrapDefaultExport({
  first: { one: 'First' },
  second: 'Second',
});

beforeEach(onReloadRenderer.mockClear);

testRenderer(
  'reloads renderer',
  { rendererId, fixtures, reloadRenderer: onReloadRenderer },
  async ({ reloadRenderer }) => {
    reloadRenderer({ rendererId });
    await retry(() => expect(onReloadRenderer).toBeCalled());
  }
);
