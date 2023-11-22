import { createRendererUrl, decodeRendererUrlFixture } from './rendererUrl.js';

const fixtureId = { path: '/path/to/fixture.js', name: 'first' };

describe('static renderer URL', () => {
  describe('root host path', () => {
    const rendererUrl = 'http://localhost:5000';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual('http://localhost:5000');
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        'http://localhost:5000/?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        'http://localhost:5000/?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D&locked=true'
      );
    });
  });

  describe('nested host path', () => {
    const rendererUrl = 'http://localhost:5000/renderer.html';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual(
        'http://localhost:5000/renderer.html'
      );
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        'http://localhost:5000/renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        'http://localhost:5000/renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D&locked=true'
      );
    });
  });

  describe('relative path', () => {
    const rendererUrl = 'renderer.html';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual('renderer.html');
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        'renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        'renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D&locked=true'
      );
    });
  });

  describe('root path', () => {
    const rendererUrl = '/renderer.html';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual('/renderer.html');
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        '/renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        '/renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D&locked=true'
      );
    });
  });

  describe('root nested path', () => {
    const rendererUrl = '/cosmos/renderer.html';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual('/cosmos/renderer.html');
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        '/cosmos/renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        '/cosmos/renderer.html?fixtureId=%7B%22path%22%3A%22%2Fpath%2Fto%2Ffixture.js%22%2C%22name%22%3A%22first%22%7D&locked=true'
      );
    });
  });
});

describe('dynamic renderer URL', () => {
  describe('root host path', () => {
    const rendererUrl = 'http://localhost:5000/<fixture>';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual(
        'http://localhost:5000/index'
      );
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        'http://localhost:5000/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        'http://localhost:5000/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9?locked=true'
      );
    });
  });

  describe('nested host path', () => {
    const rendererUrl = 'http://localhost:5000/cosmos/<fixture>';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual(
        'http://localhost:5000/cosmos/index'
      );
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        'http://localhost:5000/cosmos/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        'http://localhost:5000/cosmos/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9?locked=true'
      );
    });
  });

  describe('root path', () => {
    const rendererUrl = '/<fixture>';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual('/index');
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        '/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        '/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9?locked=true'
      );
    });
  });

  describe('root nested path', () => {
    const rendererUrl = '/cosmos/<fixture>';

    it('index', () => {
      expect(createRendererUrl(rendererUrl)).toEqual('/cosmos/index');
    });

    it('fixture', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, false)).toEqual(
        '/cosmos/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9'
      );
    });

    it('fixture locked', () => {
      expect(createRendererUrl(rendererUrl, fixtureId, true)).toEqual(
        '/cosmos/eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9?locked=true'
      );
    });
  });
});

it('decodes renderer URL fixture', () => {
  expect(
    decodeRendererUrlFixture(
      'eyJwYXRoIjoiL3BhdGgvdG8vZml4dHVyZS5qcyIsIm5hbWUiOiJmaXJzdCJ9'
    )
  ).toEqual(fixtureId);
});
