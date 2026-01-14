import componentLibrary from './plugins/component-library';
import testingPlugin from './plugins/create-board-text-rect';
import flex from './plugins/create-flexlayout';
import grid from './plugins/create-gridlayout';
import rulerGuides from './plugins/create-ruler-guides';
import createText from './plugins/create-text';
import group from './plugins/group';
import insertSvg from './plugins/insert-svg';
import pluginData from './plugins/plugin-data';
import comments from './plugins/create-comments';
import { Agent } from './utils/agent';

describe('Plugins', () => {
  it('create board - text - rectable', async () => {
    const agent = await Agent();
    const result = await agent.runCode(testingPlugin.toString(), {
      screenshot: 'create-board-text-rect',
    });
    expect(result).toMatchSnapshot();
  });

  it('create flex layout', async () => {
    const agent = await Agent();
    const result = await agent.runCode(flex.toString(), {
      screenshot: 'create-flexlayout',
    });
    expect(result).toMatchSnapshot();
  });

  it('create grid layout', async () => {
    const agent = await Agent();
    const result = await agent.runCode(grid.toString(), {
      screenshot: 'create-gridlayout',
    });
    expect(result).toMatchSnapshot();
  });

  it('group and ungroup', async () => {
    const agent = await Agent();
    const result = await agent.runCode(group.toString(), {
      screenshot: 'group-ungroup',
    });
    expect(result).toMatchSnapshot();
  });

  it('insert svg', async () => {
    const agent = await Agent();
    const result = await agent.runCode(insertSvg.toString(), {
      screenshot: 'insert-svg',
    });
    expect(result).toMatchSnapshot();
  });

  it('plugin data', async () => {
    const agent = await Agent();
    const result = await agent.runCode(pluginData.toString());
    expect(result).toMatchSnapshot();
  });

  it('component library', async () => {
    const agent = await Agent();
    const result = await agent.runCode(componentLibrary.toString(), {
      screenshot: 'component-library',
    });
    expect(result).toMatchSnapshot();
  });

  it('text and textrange', async () => {
    const agent = await Agent();
    const result = await agent.runCode(createText.toString(), {
      screenshot: 'create-text',
    });
    expect(result).toMatchSnapshot();
  });

  it('ruler guides', async () => {
    const agent = await Agent();
    const result = await agent.runCode(rulerGuides.toString(), {
      screenshot: 'create-ruler-guides',
    });
    expect(result).toMatchSnapshot();
  });

  it('comments', async () => {
    const agent = await Agent();
    const result = await agent.runCode(comments.toString(), {
      screenshot: 'create-comments',
      avoidSavedStatus: true,
    });
    expect(result).toMatchSnapshot();
  });
});
