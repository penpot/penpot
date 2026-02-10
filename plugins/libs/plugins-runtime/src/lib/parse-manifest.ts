import { Manifest } from './models/manifest.model.js';
import { manifestSchema } from './models/manifest.schema.js';

export function getValidUrl(host: string, path: string): URL {
  return new URL(path, host);
}

export function prepareUrl(manifest: Manifest, url: string, params: object): string {
  const result = getValidUrl(manifest.host, url);
  for (const [k, v] of Object.entries(params)) {
    if (!result.searchParams.has(k)) {
      result.searchParams.set(k, v);
    }
  }

  if (manifest.version === undefined || manifest.version === 1) {
    return result.toString();
  } else if (manifest.version === 2) {
    const queryString = result.searchParams.toString();
    result.search = '';
    result.hash = `/?${queryString}`;
    return result.toString();
  } else {
    throw new Error('invalid manifest version');
  }
}

export function loadManifest(url: string): Promise<Manifest> {
  return fetch(url)
    .then((response) => response.json())
    .then((manifest: Manifest): Manifest => {
      const parseResult = manifestSchema.safeParse(manifest);

      if (!parseResult.success) {
        throw new Error('Invalid plugin manifest');
      }

      return manifest;
    })
    .catch((error) => {
      console.error(error);
      throw error;
    });
}

export function loadManifestCode(manifest: Manifest): Promise<string> {
  if (!manifest.host && !manifest.code.startsWith('http')) {
    return Promise.resolve(manifest.code);
  }

  return fetch(getValidUrl(manifest.host, manifest.code)).then((response) => {
    if (response.ok) {
      return response.text();
    }

    throw new Error('Failed to load plugin code');
  });
}
