import until from 'async-until';
import { findLast } from 'lodash-es';
import {
  FixtureListUpdateResponse,
  FixtureState,
  FixtureStateChangeResponse,
  ReloadRendererRequest,
  RendererReadyResponse,
  RendererRequest,
  RendererResponse,
  SelectFixtureRequest,
  SetFixtureStateRequest,
  UnselectFixtureRequest,
} from 'react-cosmos-core';

export type RendererConnectTestApi = {
  pingRenderers: () => void;
  reloadRenderer: (payload: ReloadRendererRequest['payload']) => void;
  selectFixture: (payload: SelectFixtureRequest['payload']) => void;
  unselectFixture: (payload: UnselectFixtureRequest['payload']) => void;
  setFixtureState: (payload: SetFixtureStateRequest['payload']) => void;
  rendererReady: (payload: RendererReadyResponse['payload']) => Promise<void>;
  fixtureListUpdate: (
    payload: FixtureListUpdateResponse['payload']
  ) => Promise<void>;
  fixtureStateChange: (
    payload: FixtureStateChangeResponse['payload']
  ) => Promise<void>;
  getLastFixtureState: () => Promise<FixtureState>;
  clearResponses: () => void;
};

export function createRendererConnectTestApi(args: {
  getResponses: () => RendererResponse[];
  postRequest: (msg: RendererRequest) => unknown | Promise<unknown>;
  clearResponses: () => void;
}): RendererConnectTestApi {
  return {
    pingRenderers,
    reloadRenderer,
    selectFixture,
    unselectFixture,
    setFixtureState,
    rendererReady,
    fixtureListUpdate,
    fixtureStateChange,
    getLastFixtureState,
    clearResponses: args.clearResponses,
  };

  function pingRenderers() {
    return args.postRequest({
      type: 'pingRenderers',
    });
  }

  function reloadRenderer(payload: ReloadRendererRequest['payload']) {
    return args.postRequest({
      type: 'reloadRenderer',
      payload,
    });
  }

  function selectFixture(payload: SelectFixtureRequest['payload']) {
    return args.postRequest({
      type: 'selectFixture',
      payload,
    });
  }

  function unselectFixture(payload: UnselectFixtureRequest['payload']) {
    return args.postRequest({
      type: 'unselectFixture',
      payload,
    });
  }

  function setFixtureState(payload: SetFixtureStateRequest['payload']) {
    return args.postRequest({
      type: 'setFixtureState',
      payload,
    });
  }

  async function rendererReady(payload: RendererReadyResponse['payload']) {
    await untilResponse({
      type: 'rendererReady',
      payload,
    });
  }

  async function fixtureListUpdate(
    payload: FixtureListUpdateResponse['payload']
  ) {
    await untilResponse({
      type: 'fixtureListUpdate',
      payload,
    });
  }

  async function fixtureStateChange(
    payload: FixtureStateChangeResponse['payload']
  ) {
    await untilResponse({
      type: 'fixtureStateChange',
      payload,
    });
  }

  async function getLastFixtureState() {
    const msg =
      await getLastResponseOfType<FixtureStateChangeResponse>(
        'fixtureStateChange'
      );
    return msg.payload.fixtureState;
  }

  async function untilResponse(msg: RendererResponse) {
    try {
      await until(
        () => {
          try {
            // Support expect.any(constructor) matches
            // https://jestjs.io/docs/en/expect#expectanyconstructor
            expect(findLastResponseWithType(msg.type)).toEqual(msg);
            return true;
          } catch (err) {
            return false;
          }
        },
        { timeout: 3000 }
      );
    } catch (err) {
      expect(findLastResponseWithType(msg.type)).toEqual(msg);
    }
  }

  async function getLastResponseOfType<M extends RendererResponse>(
    msgType: string
  ): Promise<M> {
    let lastMsg = null as null | RendererResponse;

    try {
      await until(
        () => {
          lastMsg = getLastResponse();
          return lastMsg && lastMsg.type === msgType;
        },
        { timeout: 1000 }
      );
    } finally {
      if (!lastMsg || lastMsg.type !== msgType) {
        throw new Error(`"${msgType}" message never arrived`);
      }
    }

    return lastMsg as M;
  }

  function getLastResponse(): null | RendererResponse {
    const messages = args.getResponses();
    return messages.length === 0 ? null : messages[messages.length - 1];
  }

  function findLastResponseWithType(type: string): null | RendererResponse {
    const messages = args.getResponses();
    return findLast(messages, msg => msg.type === type) ?? null;
  }
}
