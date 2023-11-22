import { Base64 } from 'js-base64';
import { CosmosCommand } from '../server/serverTypes.js';
import { FixtureId } from '../userModules/fixtureTypes.js';
import { buildRendererQueryString } from './rendererQueryString.js';

export function createRendererUrl(
  rendererUrl: string,
  fixtureId?: FixtureId,
  locked?: boolean
) {
  if (hasFixtureVar(rendererUrl)) {
    if (!fixtureId) return replaceFixtureVar(rendererUrl, 'index');

    return (
      replaceFixtureVar(rendererUrl, encodeRendererUrlFixture(fixtureId)) +
      buildRendererQueryString({ locked })
    );
  } else {
    if (!fixtureId) return rendererUrl;

    const baseUrl = hostOnlyUrl(rendererUrl) ? rendererUrl + '/' : rendererUrl;
    return baseUrl + buildRendererQueryString({ fixtureId, locked });
  }
}

export function pickRendererUrl(
  rendererUrl: undefined | null | string | { dev: string; export: string },
  command: CosmosCommand
): null | string {
  return rendererUrl && typeof rendererUrl === 'object'
    ? rendererUrl[command]
    : rendererUrl ?? null;
}

export function encodeRendererUrlFixture(fixtureId: FixtureId) {
  return Base64.encode(JSON.stringify(fixtureId));
}

export function decodeRendererUrlFixture(fixture: string): FixtureId {
  return JSON.parse(Base64.decode(fixture));
}

function hasFixtureVar(rendererUrl: string) {
  return rendererUrl.includes('<fixture>');
}

function replaceFixtureVar(rendererUrl: string, fixture: string) {
  return rendererUrl.replace(/<fixture>/g, fixture);
}

function hostOnlyUrl(url: string) {
  try {
    const { protocol, pathname } = new URL(url);
    return (protocol === 'http:' || protocol === 'https:') && pathname === '/';
  } catch (err) {
    return false;
  }
}
