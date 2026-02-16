import type {
  ActiveUser,
  Board,
  Boolean,
  BooleanType,
  Color,
  ColorShapeInfo,
  Ellipse,
  EventsMap,
  File,
  Flags,
  FontsContext,
  Group,
  HistoryContext,
  LibraryComponent,
  LibraryContext,
  LibraryVariantComponent,
  LocalStorage,
  Page,
  Path,
  Penpot,
  Rectangle,
  Shape,
  SvgRaw,
  Text,
  Theme,
  User,
  VariantContainer,
  Viewport,
} from '@penpot/plugin-types';

import { Permissions } from '../models/manifest.model.js';
import { OpenUIOptions } from '../models/open-ui-options.model.js';
import { z } from 'zod';
import { createPluginManager } from '../plugin-manager.js';

export const validEvents = [
  'finish',
  'pagechange',
  'filechange',
  'selectionchange',
  'themechange',
  'shapechange',
  'contentsave',
] as const;

export function createApi(
  plugin: Awaited<ReturnType<typeof createPluginManager>>,
) {
  const checkPermission = (permission: Permissions) => {
    if (!plugin.manifest.permissions.includes(permission)) {
      throw new Error(`Permission ${permission} is not granted`);
    }
  };

  const penpot: Penpot = {
    ui: {
      open: (name: string, url: string, options?: OpenUIOptions) => {
        plugin.openModal(name, url, options);
      },

      get size() {
        return plugin.getModal()?.size() || null;
      },

      resize: (width: number, height: number) => {
        return plugin.resizeModal(width, height);
      },

      sendMessage(message: unknown) {
        const event = new CustomEvent('message', {
          detail: message,
        });

        plugin.getModal()?.dispatchEvent(event);
      },

      onMessage: <T>(callback: (message: T) => void) => {
        z.function().parse(callback);

        plugin.registerMessageCallback(callback as (message: unknown) => void);
      },
    },

    utils: {
      geometry: {
        center(shapes: Shape[]) {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          return (window as any).app.plugins.public_utils.centerShapes(shapes);
        },
      },
      types: {
        isBoard(shape: Shape): shape is Board {
          return shape.type === 'board';
        },
        isGroup(shape: Shape): shape is Group {
          return shape.type === 'group';
        },
        isMask(shape: Shape): shape is Group {
          return shape.type === 'group' && shape.isMask();
        },
        isBool(shape: Shape): shape is Boolean {
          return shape.type === 'boolean';
        },
        isRectangle(shape: Shape): shape is Rectangle {
          return shape.type === 'rectangle';
        },
        isPath(shape: Shape): shape is Path {
          return shape.type === 'path';
        },
        isText(shape: Shape): shape is Text {
          return shape.type === 'text';
        },
        isEllipse(shape: Shape): shape is Ellipse {
          return shape.type === 'ellipse';
        },
        isSVG(shape: Shape): shape is SvgRaw {
          return shape.type === 'svg-raw';
        },
        isVariantContainer(shape: Shape): shape is VariantContainer {
          return shape.type === 'board' && shape.isVariantContainer();
        },
        isVariantComponent(
          component: LibraryComponent,
        ): component is LibraryVariantComponent {
          return component.isVariant();
        },
      },
    },

    closePlugin: () => {
      plugin.close();
    },
    on<T extends keyof EventsMap>(
      type: T,
      callback: (event: EventsMap[T]) => void,
      props?: { [key: string]: unknown },
    ): symbol {
      // z.function alter fn, so can't use it here
      z.enum(validEvents).parse(type);
      z.function().parse(callback);

      // To suscribe to events needs the read permission
      checkPermission('content:read');

      return plugin.registerListener(type, callback, props);
    },

    off(eventId: symbol): void {
      plugin.destroyListener(eventId);
    },

    // Penpot State API

    get root(): Shape | null {
      checkPermission('content:read');
      return plugin.context.root;
    },

    get currentFile(): File | null {
      checkPermission('content:read');
      return plugin.context.currentFile;
    },

    get currentPage(): Page | null {
      checkPermission('content:read');
      return plugin.context.currentPage;
    },

    get selection(): Shape[] {
      checkPermission('content:read');
      return plugin.context.selection;
    },

    set selection(value: Shape[]) {
      checkPermission('content:read');
      plugin.context.selection = value;
    },

    get viewport(): Viewport {
      return plugin.context.viewport;
    },

    get history(): HistoryContext {
      return plugin.context.history;
    },

    get library(): LibraryContext {
      checkPermission('library:read');
      return plugin.context.library;
    },

    get fonts(): FontsContext {
      checkPermission('content:read');
      return plugin.context.fonts;
    },

    get flags(): Flags {
      return plugin.context.flags;
    },

    get currentUser(): User {
      checkPermission('user:read');
      return plugin.context.currentUser;
    },

    get activeUsers(): ActiveUser[] {
      checkPermission('user:read');
      return plugin.context.activeUsers;
    },

    shapesColors(shapes: Shape[]): (Color & ColorShapeInfo)[] {
      checkPermission('content:read');
      return plugin.context.shapesColors(shapes);
    },

    replaceColor(shapes: Shape[], oldColor: Color, newColor: Color) {
      checkPermission('content:write');
      return plugin.context.replaceColor(shapes, oldColor, newColor);
    },

    get theme(): Theme {
      return plugin.context.theme;
    },

    get localStorage(): LocalStorage {
      checkPermission('allow:localstorage');
      return plugin.context.localStorage;
    },

    createBoard(): Board {
      checkPermission('content:write');
      return plugin.context.createBoard();
    },

    createRectangle(): Rectangle {
      checkPermission('content:write');
      return plugin.context.createRectangle();
    },

    createEllipse(): Ellipse {
      checkPermission('content:write');
      return plugin.context.createEllipse();
    },

    createText(text: string): Text | null {
      checkPermission('content:write');
      return plugin.context.createText(text);
    },

    createPath(): Path {
      checkPermission('content:write');
      return plugin.context.createPath();
    },

    createBoolean(boolType: BooleanType, shapes: Shape[]): Boolean | null {
      checkPermission('content:write');
      return plugin.context.createBoolean(boolType, shapes);
    },

    createShapeFromSvg(svgString: string): Group | null {
      checkPermission('content:write');
      return plugin.context.createShapeFromSvg(svgString);
    },

    createShapeFromSvgWithImages(svgString: string): Promise<Group | null> {
      checkPermission('content:write');
      return plugin.context.createShapeFromSvgWithImages(svgString);
    },

    group(shapes: Shape[]): Group | null {
      checkPermission('content:write');
      return plugin.context.group(shapes);
    },

    ungroup(group: Group, ...other: Group[]): void {
      checkPermission('content:write');
      plugin.context.ungroup(group, ...other);
    },

    uploadMediaUrl(name: string, url: string) {
      checkPermission('content:write');
      return plugin.context.uploadMediaUrl(name, url);
    },

    uploadMediaData(name: string, data: Uint8Array, mimeType: string) {
      checkPermission('content:write');
      return plugin.context.uploadMediaData(name, data, mimeType);
    },

    generateMarkup(
      shapes: Shape[],
      options?: { type?: 'html' | 'svg' },
    ): string {
      checkPermission('content:read');
      return plugin.context.generateMarkup(shapes, options);
    },

    generateStyle(
      shapes: Shape[],
      options?: {
        type?: 'css';
        withPrelude?: boolean;
        includeChildren?: boolean;
      },
    ): string {
      checkPermission('content:read');
      return plugin.context.generateStyle(shapes, options);
    },

    generateFontFaces(shapes: Shape[]): Promise<string> {
      checkPermission('content:read');
      return plugin.context.generateFontFaces(shapes);
    },

    openViewer(): void {
      checkPermission('content:read');
      plugin.context.openViewer();
    },

    createPage(): Page {
      checkPermission('content:write');
      return plugin.context.createPage();
    },

    openPage(page: Page, newWindow?: boolean): void {
      checkPermission('content:read');
      plugin.context.openPage(page, newWindow ?? true);
    },

    alignHorizontal(
      shapes: Shape[],
      direction: 'left' | 'center' | 'right',
    ): void {
      checkPermission('content:write');
      plugin.context.alignHorizontal(shapes, direction);
    },

    alignVertical(
      shapes: Shape[],
      direction: 'top' | 'center' | 'bottom',
    ): void {
      checkPermission('content:write');
      plugin.context.alignVertical(shapes, direction);
    },

    distributeHorizontal(shapes: Shape[]): void {
      checkPermission('content:write');
      plugin.context.distributeHorizontal(shapes);
    },

    distributeVertical(shapes: Shape[]): void {
      checkPermission('content:write');
      plugin.context.distributeVertical(shapes);
    },

    flatten(shapes: Shape[]): Path[] {
      checkPermission('content:write');
      return plugin.context.flatten(shapes);
    },
  };

  return {
    penpot,
  };
}
