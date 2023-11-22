import React from 'react';
import { FixtureDecoratorId } from 'react-cosmos-core';
import { ElRefs } from './shared.js';
import { useFixtureState } from './useFixtureState.js';
import { useReadClassState } from './useReadClassState.js';

export function useClassStateCapture(
  fixture: React.ReactNode,
  decoratorId: FixtureDecoratorId
) {
  const elRefs = React.useRef<ElRefs>({});
  React.useEffect(() => {
    return () => {
      elRefs.current = {};
    };
  }, []);

  useReadClassState(fixture, decoratorId, elRefs);
  return useFixtureState(fixture, decoratorId, elRefs);
}
