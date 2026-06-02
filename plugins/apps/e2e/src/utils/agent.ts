import puppeteer, { ConsoleMessage } from 'puppeteer';
import { PenpotApi } from './api';
import { getFileUrl } from './get-file-url';
import { idObjectToArray } from './clean-id';
import { Shape } from '../models/shape.model';

const screenshotsEnable = process.env['E2E_SCREENSHOTS'] === 'true';

function replaceIds(shapes: Shape[]) {
  let id = 1;

  const getId = () => {
    return String(id++);
  };

  function replaceChildrenId(id: string, newId: string) {
    for (const node of shapes) {
      if (node.parentId === id) {
        node.parentId = newId;
      }

      if (node.frameId === id) {
        node.frameId = newId;
      }

      if (node.shapes) {
        node.shapes = node.shapes?.map((shapeId) => {
          return shapeId === id ? newId : shapeId;
        });
      }

      if (node.layoutGridCells) {
        node.layoutGridCells = idObjectToArray(node.layoutGridCells, newId);
      }
    }
  }

  for (const node of shapes) {
    const previousId = node.id;

    node.id = getId();

    replaceChildrenId(previousId, node.id);
  }
}

export async function Agent() {
  console.log('Initializing Penpot API...');
  const penpotApi = await PenpotApi();

  console.log('Creating file...');
  const file = await penpotApi.createFile();
  console.log('File created with id:', file['~:id']);

  const fileUrl = getFileUrl(file);
  console.log('File URL:', fileUrl);

  console.log('Launching browser...');
  const browser = await puppeteer.launch({
    headless: process.env['E2E_HEADLESS'] !== 'false',
    args: ['--ignore-certificate-errors'],
  });
  const page = await browser.newPage();

  await page.setViewport({ width: 1920, height: 1080 });
  await page.setExtraHTTPHeaders({
    'X-Client': 'plugins/e2e:puppeter',
  });

  console.log('Setting authentication cookie...');
  page.setCookie({
    name: 'auth-token',
    value: penpotApi.getAuth().split('=')[1],
    domain: 'localhost',
    path: '/',
    expires: (Date.now() + 3600 * 1000) / 1000,
  });

  console.log('Navigating to file URL...');
  await page.goto(fileUrl);
  await page.waitForSelector('[data-testid="viewport"]');
  console.log('Page loaded and viewport selector found.');

  page
    .on('console', async (message) => {
      console.log(`${message.type()} ${message.text()}`);
    })
    .on('pageerror', (message) => {
      console.error('Page error:', message);
    });

  const finish = async () => {
    console.log('Deleting file and closing browser...');
    // TODO
    // await penpotApi.deleteFile(file['~:id']);
    if (process.env['E2E_CLOSE_BROWSER'] !== 'false') {
      await browser.close();
    }
    console.log('Clean up done.');
  };

  return {
    async runCode(
      code: string,
      options: {
        screenshot?: string;
        autoFinish?: boolean;
      } = {
        screenshot: '',
        autoFinish: true,
      },
    ) {
      const autoFinish = options.autoFinish ?? true;

      console.log('Running plugin code...');
      await page.evaluate((testingPlugin) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).ɵloadPlugin({
          pluginId: '00000000-0000-0000-0000-000000000000',
          name: 'Test',
          code: `
            (${testingPlugin})();
          `,
          icon: '',
          description: '',
          permissions: [
            'content:read',
            'content:write',
            'library:read',
            'library:write',
            'user:read',
            'comment:read',
            'comment:write',
            'allow:downloads',
            'allow:localstorage',
          ],
        });
      }, code);

      if (options.screenshot && screenshotsEnable) {
        console.log('Taking screenshot:', options.screenshot);
        await page.screenshot({
          path: 'screenshots/' + options.screenshot + '.png',
        });
      }

      const result = await new Promise((resolve) => {
        const handleConsole = async (msg: ConsoleMessage) => {
          const args = (await Promise.all(
            msg.args().map((arg) => arg.jsonValue()),
          )) as unknown[];

          const type = args[0];
          const data = args[1];

          if (type !== 'objects' || !data || typeof data !== 'object') {
            console.log('Invalid console message, waiting for valid one...');
            page.once('console', handleConsole);
            return;
          }

          const result = Object.values(data) as Shape[];

          replaceIds(result);
          console.log('IDs replaced in result.');

          resolve(result);
        };

        page.once('console', handleConsole);

        console.log('Evaluating debug.dump_objects...');
        page.evaluate(`
          debug.dump_objects();
        `);
      });

      await page.waitForNetworkIdle({ idleTime: 2000 });

      // Wait for the update-file API call to complete
      if (process.env['E2E_WAIT_API_RESPONSE'] === 'true') {
        await page.waitForResponse(
          (response) =>
            response.url().includes('api/main/methods/update-file') &&
            response.status() === 200,
          { timeout: 10000 },
        );
      }

      if (autoFinish) {
        console.log('Auto finish enabled. Cleaning up...');
        await finish();
      }

      return result;
    },
    finish,
  };
}
