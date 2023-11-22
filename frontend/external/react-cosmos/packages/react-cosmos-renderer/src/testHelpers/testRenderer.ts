import {
  mountTestRenderer,
  RendererTestArgs,
  RendererTestCallback,
} from './mountTestRenderer.js';

export function testRenderer(
  testName: string,
  args: RendererTestArgs,
  cb: RendererTestCallback
) {
  const test = () => mountTestRenderer(args, cb);

  if (args.only) {
    it.only(testName, test);
  } else {
    it(testName, test);
  }
}
