import puppeteer from 'puppeteer';
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
  const browser = await puppeteer.launch({});
  const page = await browser.newPage();

  await page.setViewport({ width: 1920, height: 1080 });

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
    await penpotApi.deleteFile(file['~:id']);
    await browser.close();
    console.log('Clean up done.');
  };

  return {
    async runCode(
      code: string,
      options: {
        screenshot?: string;
        autoFinish?: boolean;
        avoidSavedStatus?: boolean;
      } = {
        screenshot: '',
        autoFinish: true,
        avoidSavedStatus: false,
      },
    ) {
      const autoFinish = options.autoFinish ?? true;

      console.log('Running plugin code...');
      await page.evaluate((testingPlugin) => {
        (globalThis as any).ɵloadPlugin({
          pluginId: 'TEST',
          name: 'Test',
          code: `
            (${testingPlugin})();
          `,
          icon: '',
          description: '',
          permissions: ['content:read', 'content:write'],
        });
      }, code);

      if (!options.avoidSavedStatus) {
        console.log('Waiting for save status...');
        await page.waitForSelector(
          '.main_ui_workspace_right_header__saved-status',
          {
            timeout: 10000,
          },
        );
        console.log('Save status found.');
      }

      if (options.screenshot && screenshotsEnable) {
        console.log('Taking screenshot:', options.screenshot);
        await page.screenshot({
          path: 'screenshots/' + options.screenshot + '.png',
        });
      }

      return new Promise((resolve) => {
        page.once('console', async (msg) => {
          const args = (await Promise.all(
            msg.args().map((arg) => arg.jsonValue()),
          )) as Record<string, unknown>[];

          const result = Object.values(args[1]) as Shape[];

          replaceIds(result);
          console.log('IDs replaced in result.');

          resolve(result);

          if (autoFinish) {
            console.log('Auto finish enabled. Cleaning up...');
            finish();
          }
        });

        console.log('Evaluating debug.dump_objects...');
        page.evaluate(`
          debug.dump_objects();
        `);
      });
    },
    finish,
  };
}
