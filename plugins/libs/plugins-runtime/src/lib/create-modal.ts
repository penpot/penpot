import type { OpenUIOptions } from './models/open-ui-options.model.js';
import type { Theme } from '@penpot/plugin-types';
import type { PluginModalElement } from './modal/plugin-modal.js';

import { parseTranslate } from './parse-translate';

export function createModal(
  name: string,
  url: string,
  theme: Theme,
  options?: OpenUIOptions,
  allowDownloads?: boolean,
) {
  const modal = document.createElement('plugin-modal') as PluginModalElement;

  modal.setTheme(theme);

  const { width } = resizeModal(modal, options?.width, options?.height);

  const initialPosition = {
    blockStart: 40,
    // To be able to resize the element as expected the position must be absolute from the right.
    // This value is the length of the window minus the width of the element plus the width of the design tab.
    inlineStart: window.innerWidth - width - 290,
  };

  if ((options as any)?.hidden) {
    modal.style.setProperty('display', 'none');
  }

  modal.style.setProperty(
    '--modal-block-start',
    `${initialPosition.blockStart}px`,
  );
  modal.style.setProperty(
    '--modal-inline-start',
    `${initialPosition.inlineStart}px`,
  );

  modal.setAttribute('title', name);
  modal.setAttribute('iframe-src', url);

  if (allowDownloads) {
    modal.setAttribute('allow-downloads', 'true');
  }

  document.body.appendChild(modal);

  return modal;
}

export function resizeModal(
  modal: PluginModalElement,
  width: number = 335,
  height: number = 590,
) {
  const minPluginWidth = 200;
  const minPluginHeight = 200;

  let wrapper = modal.shadowRoot?.querySelector('.wrapper');
  let curX = 0;
  let curY = 0;
  if (wrapper) {
    let rect = wrapper.getBoundingClientRect();
    curX = rect.x;
    curY = rect.y;
  }

  const maxWidth = window.innerWidth - 40;
  const maxHeight = window.innerHeight - 40;
  width = Math.min(width, maxWidth);
  height = Math.min(height, maxHeight);

  width = Math.max(width, minPluginWidth);
  height = Math.max(height, minPluginHeight);

  let deltax = 0;
  if (curX + width > maxWidth) {
    deltax = maxWidth - (curX + width);
  }

  let deltay = 0;
  if (curY + height > maxHeight) {
    deltay = maxHeight - (curY + height);
  }

  let { x, y } = parseTranslate(modal.wrapper);
  x = x + deltax;
  y = y + deltay;

  modal.wrapper.style.transform = `translate(${x}px, ${y}px)`;
  modal.wrapper.style.width = `${width}px`;
  modal.wrapper.style.height = `${height}px`;

  return { width, height };
}
