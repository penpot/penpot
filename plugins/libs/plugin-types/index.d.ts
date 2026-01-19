/**
 * These are methods and properties available on the `penpot` global object.
 *
 */
export interface Penpot
  extends Omit<Context, 'addListener' | 'removeListener'> {
  ui: {
    /**
     * Opens the plugin UI. It is possible to develop a plugin without interface (see Palette color example) but if you need, the way to open this UI is using `penpot.ui.open`.
     * There is a minimum and maximum size for this modal and a default size but it's possible to customize it anyway with the options parameter.
     *
     * @param name title of the plugin, it'll be displayed on the top of the modal
     * @param url of the plugin
     * @param options height and width of the modal.
     *
     * @example
     * ```js
     * penpot.ui.open('Plugin name', 'url', {width: 150, height: 300});
     * ```
     */
    open: (
      name: string,
      url: string,
      options?: { width: number; height: number },
    ) => void;

    size: {
      /**
       * Returns the size of the modal.
       * @example
       * ```js
       * const size = penpot.ui.size;
       * console.log(size);
       * ```
       */
      width: number;
      height: number;
    } | null;

    /**
     * Resizes the plugin UI.
     * @param width The width of the modal.
     * @param height The height of the modal.
     * @example
     * ```js
     * penpot.ui.resize(300, 400);
     * ```
     * */
    resize: (width: number, height: number) => void;

    /**
     * Sends a message to the plugin UI.
     *
     * @param message content usually is an object
     *
     * @example
     * ```js
     * this.sendMessage({ type: 'example-type', content: 'data we want to share' });
     * ```
     */
    sendMessage: (message: unknown) => void;
    /**
     * This is usually used in the `plugin.ts` file in order to handle the data sent by our plugin
     *
     @param callback - A function that will be called whenever a message is received.
     * The function receives a single argument, `message`, which is of type `T`.
     *
     * @example
     * ```js
     * penpot.ui.onMessage((message) => {if(message.type === 'example-type' { ...do something })});
     * ```
     */
    onMessage: <T>(callback: (message: T) => void) => void;
  };
  /**
   * Provides access to utility functions and context-specific operations.
   */
  utils: ContextUtils;
  /**
   * Closes the plugin. When this method is called the UI will be closed.
   *
   * @example
   * ```js
   * penpot.closePlugin();
   * ```
   */
  closePlugin: () => void;
  /**
   * Adds an event listener for the specified event type.
   * Subscribing to events requires `content:read` permission.
   *
   * The following are the possible event types:
   *  - pagechange: event emitted when the current page changes. The callback will receive the new page.
   *  - shapechange: event emitted when the shape changes. This event requires to send inside the `props` object the shape
   *  that will be observed. For example:
   *  ```javascript
   *  // Observe the current selected shape
   *  penpot.on('shapechange', (shape) => console.log(shape.name), { shapeId: penpot.selection[0].id });
   *  ```
   *  - selectionchange: event emitted when the current selection changes. The callback will receive the list of ids for the new selection
   *  - themechange: event emitted when the user changes its theme. The callback will receive the new theme (currently: either `dark` or `light`)
   *  - documentsaved: event emitted after the document is saved in the backend.
   *
   * @param type The event type to listen for.
   * @param callback The callback function to execute when the event is triggered.
   * @param props The properties for the current event handler. Only makes sense for specific events.
   * @return the listener id that can be used to call `off` and cancel the listener
   *
   * @example
   * ```js
   * penpot.on('pagechange', () => {...do something}).
   * ```
   */
  on<T extends keyof EventsMap>(
    type: T,
    callback: (event: EventsMap[T]) => void,
    props?: { [key: string]: unknown },
  ): symbol;

  /**
   * Removes an event listener for the specified event type.
   *
   * @param listenerId the id returned by the `on` method when the callback was set
   *
   * @example
   * ```js
   * const listenerId = penpot.on('contentsave', () => console.log("Changed"));
   * penpot.off(listenerId);
   * ```
   */
  off(listenerId: symbol): void;
}

/**
 * Type for all the possible types of actions in an interaction.
 */
export type Action =
  | NavigateTo
  | OpenOverlay
  | ToggleOverlay
  | CloseOverlay
  | PreviousScreen
  | OpenUrl;

/**
 * Represents an active user in Penpot, extending the `User` interface.
 * This interface includes additional properties specific to active users.
 */
export interface ActiveUser extends User {
  /**
   * The position of the active user.
   *
   * @example
   * ```js
   * const userPosition = activeUser.position;
   * console.log(userPosition);
   * ```
   */
  position?: { x: number; y: number };
  /**
   * The zoom level of the active user.
   *
   * @example
   * ```js
   * const userZoom = activeUser.zoom;
   * console.log(userZoom);
   * ```
   */
  readonly zoom?: number;
}

/**
 * Type of all the animations that can be added to an interaction.
 */
export type Animation = Dissolve | Slide | Push;

/**
 * Represents blur properties in Penpot.
 * This interface includes properties for defining the type and intensity of a blur effect, along with its visibility.
 */
export interface Blur {
  /**
   * The optional unique identifier for the blur effect.
   */
  id?: string;
  /**
   * The optional type of the blur effect.
   * Currently, only 'layer-blur' is supported.
   */
  type?: 'layer-blur';
  /**
   * The optional intensity value of the blur effect.
   */
  value?: number;
  /**
   * Specifies whether the blur effect is hidden.
   * Defaults to false if omitted.
   */
  hidden?: boolean;
}

/**
 * Represents a board in Penpot.
 * This interface extends `ShapeBase` and includes properties and methods specific to board.
 */
export interface Board extends ShapeBase {
  /**
   * The type of the shape, which is always 'board' for boards.
   */
  readonly type: 'board';

  /**
   * When true the board will clip the children inside
   */
  clipContent: boolean;

  /**
   * WHen true the board will be displayed in the view mode
   */
  showInViewMode: boolean;

  /**
   * The grid layout configuration of the board, if applicable.
   */
  readonly grid?: GridLayout;

  /**
   * The flex layout configuration of the board, if applicable.
   */
  readonly flex?: FlexLayout;

  /**
   * The guides associated with the board.
   */
  guides: Guide[];

  /**
   * The ruler guides attached to the board
   */
  readonly rulerGuides: RulerGuide[];

  /**
   * The horizontal sizing behavior of the board.
   */
  horizontalSizing?: 'auto' | 'fix';

  /**
   * The vertical sizing behavior of the board.
   */
  verticalSizing?: 'auto' | 'fix';

  /**
   * The fills applied to the shape.
   */
  fills: Fill[];

  // Container Properties
  /**
   * The children shapes contained within the board.
   * When writing into this property, you can only reorder the shapes, not
   * changing the structure. If the new shapes don't match the current shapes
   * it will give a validation error.
   *
   * @example
   * ```js
   * board.children = board.children.reverse();
   * ```
   */
  children: Shape[];

  /**
   * Appends a child shape to the board.
   * @param child The child shape to append.
   *
   * @example
   * ```js
   * board.appendChild(childShape);
   * ```
   */
  appendChild(child: Shape): void;

  /**
   * Inserts a child shape at the specified index within the board.
   * @param index The index at which to insert the child shape.
   * @param child The child shape to insert.
   *
   * @example
   * ```js
   * board.insertChild(0, childShape);
   * ```
   */
  insertChild(index: number, child: Shape): void;

  /**
   * Adds a flex layout configuration to the board (so it's necessary to create a board first of all).
   * @return Returns the flex layout configuration added to the board.
   * @example
   * ```js
   * const board = penpot.createBoard();
   * const flex = board.addFlexLayout();
   *
   * // You can change the flex properties as follows.
   * flex.dir = "column";
   * flex.wrap = "wrap";
   * flex.alignItems = "center";
   * flex.justifyContent = "center";
   * flex.horizontalSizing = "fill";
   * flex.verticalSizing = "fill";
   * ```
   */
  addFlexLayout(): FlexLayout;
  /**
   * Adds a grid layout configuration to the board (so it's necessary to create a board first of all). You can add rows and columns, check addRow/addColumn.
   * @return Returns the grid layout configuration added to the board.
   * @example
   * ```js
   * const board = penpot.createBoard();
   * const grid = board.addGridLayout();
   *
   * // You can change the grid properties as follows.
   * grid.alignItems = "center";
   * grid.justifyItems = "start";
   * grid.rowGap = 10;
   * grid.columnGap = 10;
   * grid.verticalPadding = 5;
   * grid.horizontalPadding = 5;
   */
  addGridLayout(): GridLayout;

  /**
   * Creates a new ruler guide.
   */
  addRulerGuide(orientation: RulerGuideOrientation, value: number): RulerGuide;

  /**
   * Removes the `guide` from the current page.
   */
  removeRulerGuide(guide: RulerGuide): void;

  /**
   * @return Returns true when the current board is a VariantContainer
   */
  isVariantContainer(): boolean;
}

/**
 * Represents a VariantContainer in Penpot
 * This interface extends `Board` and includes properties and methods specific to VariantContainer.
 */
export interface VariantContainer extends Board {
  /**
   * Access to the Variant interface, for attributes and actions over the full Variant (not only this VariantContainer)
   */
  readonly variants: Variants | null;
}

/**
 * Represents a boolean operation shape in Penpot.
 * This interface extends `ShapeBase` and includes properties and methods specific to boolean operations.
 */
export interface Boolean extends ShapeBase {
  /**
   * The type of the shape, which is always 'bool' for boolean operation shapes.
   */
  readonly type: 'boolean';

  /**
   * Converts the boolean shape to its path data representation.
   * @return Returns the path data (d attribute) as a string.
   * @deprecated Use the `d` attribute
   */
  toD(): string;

  /**
   * The content of the boolean shape, defined as the path string.
   * @deprecated Use either `d` or `commands`.
   */
  content: string;

  /**
   * The content of the boolean shape, defined as the path string.
   */
  d: string;

  /**
   * The content of the boolean shape, defined as an array of path commands.
   */
  commands: Array<PathCommand>;

  /**
   * The fills applied to the shape.
   */
  fills: Fill[];

  // Container Properties
  /**
   * The children shapes contained within the boolean shape.
   */
  readonly children: Shape[];
  /**
   * Appends a child shape to the boolean shape.
   * @param child The child shape to append.
   *
   * @example
   * ```js
   * boolShape.appendChild(childShape);
   * ```
   */
  appendChild(child: Shape): void;
  /**
   * Inserts a child shape at the specified index within the boolean shape.
   * @param index The index at which to insert the child shape.
   * @param child The child shape to insert.
   *
   * @example
   * ```js
   * boolShape.insertChild(0, childShape);
   * ```
   */
  insertChild(index: number, child: Shape): void;
}

/**
 * Represents the boolean operation types available in Penpot.
 * These types define how shapes can be combined or modified using boolean operations.
 */
export type BooleanType = 'union' | 'difference' | 'exclude' | 'intersection';

/**
 * Bounds represents the boundaries of a rectangular area,
 * defined by the coordinates of the top-left corner and the dimensions of the rectangle.
 *
 * @example
 * ```js
 * const bounds = { x: 50, y: 50, width: 200, height: 100 };
 * console.log(bounds);
 * ```
 */
export type Bounds = {
  /**
   * Top-left x position of the rectangular area defined
   */
  x: number;
  /**
   * Top-left y position of the rectangular area defined
   */
  y: number;
  /**
   * Width of the represented area
   */
  width: number;
  /**
   * Height of the represented area
   */
  height: number;
};

/**
 * This action will close a targeted board that is opened as an overlay.
 */
export interface CloseOverlay {
  /**
   * The action type
   */
  readonly type: 'close-overlay';

  /**
   * The overlay to be closed with this action.
   */
  readonly destination?: Board;

  /**
   * Animation displayed with this interaction.
   */
  readonly animation: Animation;
}

/**
 * Represents color properties in Penpot.
 * This interface includes properties for defining solid colors, gradients, and image fills, along with metadata.
 */
export interface Color {
  /**
   * The optional reference ID for an external color definition.
   */
  id?: string;
  /**
   * The optional reference to an external file for the color definition.
   */
  fileId?: string;
  /**
   * The optional name of the color.
   */
  name?: string;
  /**
   * The optional path or category to which this color belongs.
   */
  path?: string;
  /**
   * The optional solid color, represented as a string (e.g., '#FF5733').
   */
  color?: string;
  /**
   * The optional opacity level of the color, ranging from 0 (fully transparent) to 1 (fully opaque).
   * Defaults to 1 if omitted.
   */
  opacity?: number;
  /**
   * The optional reference ID for an external color definition.
   * @deprecated Use `id` instead
   */
  refId?: string;
  /**
   * The optional reference to an external file for the color definition.
   * @deprecated Use `fileId`
   */
  refFile?: string;
  /**
   * The optional gradient fill defined by a Gradient object.
   */
  gradient?: Gradient;
  /**
   * The optional image fill defined by an ImageData object.
   */
  image?: ImageData;
}

/**
 * Additional color information for the methods to extract colors from a list of shapes.
 */
export interface ColorShapeInfo {
  /**
   * List of shapes with additional information
   */
  readonly shapesInfo: ColorShapeInfoEntry[];
}

/**
 * Entry for the color shape additional information.
 */
export interface ColorShapeInfoEntry {
  /**
   * Property that has the color (example: fill, stroke...)
   */
  readonly property: string;

  /**
   * For properties that are indexes (such as fill) represent the index
   * of the color inside that property.
   */
  readonly index?: number;

  /**
   * Identifier of the shape that contains the color
   */
  readonly shapeId: string;
}

/**
 * Comments allow the team to have one priceless conversation getting and
 * providing feedback right over the designs and prototypes.
 */
export interface Comment {
  /**
   * The `user` that has created the comment.
   */
  readonly user: User;

  /**
   * The `date` the comment has been created.
   */
  readonly date: Date;

  /**
   * The `content` for the commentary. The owner can modify the comment.
   */
  content: string;

  /**
   * Remove the current comment from its comment thread. Only the owner can remove their comments.
   * Requires the `comment:write` permission.
   */
  remove(): void;
}

/**
 * Represents a list of comments one after the other. Usually these threads
 * are conversations the users have in Penpot.
 */
export interface CommentThread {
  /**
   * This is the number that is displayed on the workspace. Is an increasing
   * sequence for each comment.
   */
  readonly seqNumber: number;

  /**
   * If the thread is attached to a `board` this will have that board
   * reference.
   */
  readonly board?: Board;

  /**
   * Owner of the comment thread
   */
  readonly owner?: User;

  /**
   * The `position` in absolute coordinates in the canvas.
   */
  position: Point;

  /**
   * Whether the thread has been marked as `resolved` or not.
   */
  resolved: boolean;

  /**
   * List of `comments` ordered by creation date.
   * Requires the `comment:read` or `comment:write` permission.
   */
  findComments(): Promise<Comment[]>;

  /**
   * Creates a new comment after the last one in the thread. The current user will
   * be used as the creation user.
   * Requires the `comment:write` permission.
   */
  reply(content: string): Promise<Comment>;

  /**
   * Removes the current comment thread. Only the user that created the thread can
   * remove it.
   * Requires the `comment:write` permission.
   */
  remove(): void;
}

/**
 * CommonLayout represents a common layout interface in the Penpot application.
 * It includes various properties for alignment, spacing, padding, and sizing, as well as a method to remove the layout.
 */
export interface CommonLayout {
  /**
   * The `alignItems` property specifies the default alignment for items inside the container.
   * It can be one of the following values:
   * - 'start': Items are aligned at the start.
   * - 'end': Items are aligned at the end.
   * - 'center': Items are centered.
   * - 'stretch': Items are stretched to fill the container.
   */
  alignItems?: 'start' | 'end' | 'center' | 'stretch';
  /**
   * The `alignContent` property specifies how the content is aligned within the container when there is extra space.
   * It can be one of the following values:
   * - 'start': Content is aligned at the start.
   * - 'end': Content is aligned at the end.
   * - 'center': Content is centered.
   * - 'space-between': Content is distributed with space between.
   * - 'space-around': Content is distributed with space around.
   * - 'space-evenly': Content is distributed with even space around.
   * - 'stretch': Content is stretched to fill the container.
   */
  alignContent?:
    | 'start'
    | 'end'
    | 'center'
    | 'space-between'
    | 'space-around'
    | 'space-evenly'
    | 'stretch';
  /**
   * The `justifyItems` property specifies the default justification for items inside the container.
   * It can be one of the following values:
   * - 'start': Items are justified at the start.
   * - 'end': Items are justified at the end.
   * - 'center': Items are centered.
   * - 'stretch': Items are stretched to fill the container.
   */
  justifyItems?: 'start' | 'end' | 'center' | 'stretch';
  /**
   * The `justifyContent` property specifies how the content is justified within the container when there is extra space.
   * It can be one of the following values:
   * - 'start': Content is justified at the start.
   * - 'center': Content is centered.
   * - 'end': Content is justified at the end.
   * - 'space-between': Content is distributed with space between.
   * - 'space-around': Content is distributed with space around.
   * - 'space-evenly': Content is distributed with even space around.
   * - 'stretch': Content is stretched to fill the container.
   */
  justifyContent?:
    | 'start'
    | 'center'
    | 'end'
    | 'space-between'
    | 'space-around'
    | 'space-evenly'
    | 'stretch';

  /**
   * The `rowGap` property specifies the gap between rows in the layout.
   */
  rowGap: number;
  /**
   * The `columnGap` property specifies the gap between columns in the layout.
   */
  columnGap: number;

  /**
   * The `verticalPadding` property specifies the vertical padding inside the container.
   */
  verticalPadding: number;
  /**
   * The `horizontalPadding` property specifies the horizontal padding inside the container.
   */
  horizontalPadding: number;

  /**
   * The `topPadding` property specifies the padding at the top of the container.
   */
  topPadding: number;
  /**
   * The `rightPadding` property specifies the padding at the right of the container.
   */
  rightPadding: number;
  /**
   * The `bottomPadding` property specifies the padding at the bottom of the container.
   */
  bottomPadding: number;
  /**
   * The `leftPadding` property specifies the padding at the left of the container.
   */
  leftPadding: number;

  /**
   * The `horizontalSizing` property specifies the horizontal sizing behavior of the container.
   * It can be one of the following values:
   * - 'fit-content': The container fits the content.
   * - 'fill': The container fills the available space.
   * - 'auto': The container size is determined automatically.
   */
  horizontalSizing: 'fit-content' | 'fill' | 'auto';
  /**
   * The `verticalSizing` property specifies the vertical sizing behavior of the container.
   * It can be one of the following values:
   * - 'fit-content': The container fits the content.
   * - 'fill': The container fills the available space.
   * - 'auto': The container size is determined automatically.
   */
  verticalSizing: 'fit-content' | 'fill' | 'auto';

  /**
   * The `remove` method removes the layout.
   */
  remove(): void;
}

/**
 * Represents the context of Penpot, providing access to various Penpot functionalities and data.
 */
export interface Context {
  /**
   * The root shape in the current Penpot context. Requires `content:read` permission.
   *
   * @example
   * ```js
   * const rootShape = context.root;
   * console.log(rootShape);
   * ```
   */
  readonly root: Shape | null;
  /**
   * Retrieves file data from the current Penpot context. Requires `content:read` permission.
   * @return Returns the file data or `null` if no file is available.
   *
   * @example
   * ```js
   * const fileData = context.currentFile;
   * console.log(fileData);
   * ```
   */
  readonly currentFile: File | null;
  /**
   * The current page in the Penpot context. Requires `content:read` permission.
   *
   * @example
   * ```js
   * const currentPage = context.currentPage;
   * console.log(currentPage);
   * ```
   */
  readonly currentPage: Page | null;
  /**
   * The viewport settings in the Penpot context.
   *
   * @example
   * ```js
   * const viewportSettings = context.viewport;
   * console.log(viewportSettings);
   * ```
   */
  readonly viewport: Viewport;

  /**
   * Provides flags to customize the API behavior.
   */
  readonly flags: Flags;

  /**
   * Context encapsulating the history operations
   *
   * @example
   * ```js
   * const historyContext = context.history;
   * console.log(historyContext);
   * ```
   */
  readonly history: HistoryContext;

  /**
   * The library context in the Penpot context, including both local and connected libraries. Requires `library:read` permission.
   *
   * @example
   * ```js
   * const libraryContext = context.library;
   * console.log(libraryContext);
   * ```
   */
  readonly library: LibraryContext;
  /**
   * The fonts context in the Penpot context, providing methods to manage fonts. Requires `content:read` permission.
   *
   * @example
   * ```js
   * const fontsContext = context.fonts;
   * console.log(fontsContext);
   * ```
   */
  readonly fonts: FontsContext;
  /**
   * The current user in the Penpot context. Requires `user:read` permission.
   *
   * @example
   * ```js
   * const currentUser = context.currentUser;
   * console.log(currentUser);
   * ```
   */
  readonly currentUser: User;
  /**
   * An array of active users in the Penpot context. Requires `user:read` permission.
   *
   * @example
   * ```js
   * const activeUsers = context.activeUsers;
   * console.log(activeUsers);
   * ```
   */
  readonly activeUsers: ActiveUser[];

  /**
   * The current theme (light or dark) in Penpot.
   *
   * @example
   * ```js
   * const currentTheme = context.theme;
   * console.log(currentTheme);
   * ```
   */
  readonly theme: Theme;

  /**
   * Access to the localStorage proxy
   */
  readonly localStorage: LocalStorage;

  /**
   * The currently selected shapes in Penpot. Requires `content:read` permission.
   *
   * @example
   * ```js
   * const selectedShapes = context.selection;
   * console.log(selectedShapes);
   * ```
   */
  selection: Shape[];

  /**
   * Retrieves colors applied to the given shapes in Penpot. Requires `content:read` permission.
   * @return Returns an array of colors and their shape information.
   *
   * @example
   * ```js
   * const colors = context.shapesColors(shapes);
   * console.log(colors);
   * ```
   */
  shapesColors(shapes: Shape[]): (Color & ColorShapeInfo)[];

  /**
   * Replaces a specified old color with a new color in the given shapes. Requires `content:write` permission.
   *
   * @example
   * ```js
   * context.replaceColor(shapes, oldColor, newColor);
   * ```
   */
  replaceColor(shapes: Shape[], oldColor: Color, newColor: Color): void;

  /**
   * Uploads media to Penpot and retrieves its image data. Requires `content:write` permission.
   * @param name The name of the media.
   * @param url The URL of the media to be uploaded.
   * @return Returns a promise that resolves to the image data of the uploaded media.
   *
   * @example
   * ```js
   * const imageData = await context.uploadMediaUrl('example', 'https://example.com/image.jpg');
   * console.log(imageData);
   *
   * // to insert the image in a shape we can do
   * const board = penpot.createBoard();
   * const shape = penpot.createRectangle();
   * board.appendChild(shape);
   * shape.fills = [{ fillOpacity: 1, fillImage: imageData }];
   * ```
   */
  uploadMediaUrl(name: string, url: string): Promise<ImageData>;

  /**
   * Uploads media to penpot and retrieves the image data. Requires `content:write` permission.
   * @param name The name of the media.
   * @param data The image content data
   * @return Returns a promise that resolves to the image data of the uploaded media.
   *
   * @example
   * ```js
   * const imageData = await context.uploadMediaData('example', imageData, 'image/jpeg');
   * console.log(imageData);
   * ```
   */
  uploadMediaData(
    name: string,
    data: Uint8Array,
    mimeType: string,
  ): Promise<ImageData>;

  /**
   * Groups the specified shapes. Requires `content:write` permission.
   * @param shapes - An array of shapes to group.
   * @return Returns the newly created group or `null` if the group could not be created.
   * @example
   * ```js
   * const penpotShapesArray = penpot.selection;
   * penpot.group(penpotShapesArray);
   * ```
   */
  group(shapes: Shape[]): Group | null;
  /**
   * Ungroups the specified group. Requires `content:write` permission.
   * @param group - The group to ungroup.
   * @param other - Additional groups to ungroup.
   *
   * @example
   * ```js
   * const penpotShapesArray = penpot.selection;
   * // We need to make sure that something is selected, and if the selected shape is a group,
   * if (selected.length && penpot.utils.types.isGroup(penpotShapesArray[0])) {
   *   penpot.group(penpotShapesArray[0]);
   * }
   * ```
   */
  ungroup(group: Group, ...other: Group[]): void;

  /**
   * Use this method to create the shape of a rectangle. Requires `content:write` permission.
   *
   * @example
   * ```js
   * const shape = penpot.createRectangle();
   * // just change the values like this
   * shape.name = "Example rectangle";
   *
   * // for solid color
   * shape.fills = [{ fillColor: "#7EFFF5" }];
   * // for linear gradient color
   * shape.fills = [{
   *  fillColorGradient: {
   *    "type": "linear",
   *    "startX": 0.5,
   *    "startY": 0,
   *    "endX": 0.5,
   *    "endY": 1,
   *    "width": 1,
   *    "stops": [
   *      {
   *        "color": "#003ae9",
   *        "opacity": 1,
   *        "offset": 0
   *      },
   *      {
   *        "color": "#003ae9",
   *        "opacity": 0,
   *        "offset": 1
   *      }
   *    ]
   *  }
   *}];
   * // for a image fill
   * const imageData = await context.uploadMediaUrl('example', 'https://example.com/image.jpg');
   * shape.fills = [{ fillOpacity: 1, fillImage: imageData }];
   *
   * shape.borderRadius = 8;
   * shape.strokes = [
   *  {
   *    strokeColor: "#2e3434",
   *    strokeStyle: "solid",
   *    strokeWidth: 2,
   *    strokeAlignment: "center",
   *  },
   *];
   * ```
   */
  createRectangle(): Rectangle;
  /**
   * Use this method to create a board. This is the first step before anything else, the container. Requires `content:write` permission.
   * Then you can add a gridlayout, flexlayout or add a shape inside the board.
   * Just a heads-up: board is a board in Penpot UI.
   *
   * @example
   * ```js
   * const board = penpot.createBoard();
   *
   * // to add grid layout
   * board.addGridLayout();
   * // to add flex layout
   * board.addFlexLayout();
   *
   * // to create a shape inside the board
   * const shape = penpot.createRectangle();
   * board.appendChild(shape);
   * ```
   */
  createBoard(): Board;
  /**
   * Use this method to create the shape of an ellipse. Requires `content:write` permission.
   *
   * @example
   * ```js
   * const shape = penpot.createEllipse();
   * // just change the values like this
   * shape.name = "Example ellipse";
   *
   * // for solid color
   * shape.fills = [{ fillColor: "#7EFFF5" }];
   * // for linear gradient color
   * shape.fills = [{
   *  fillColorGradient: {
   *    "type": "linear",
   *    "startX": 0.5,
   *    "startY": 0,
   *    "endX": 0.5,
   *    "endY": 1,
   *    "width": 1,
   *    "stops": [
   *      {
   *        "color": "#003ae9",
   *        "opacity": 1,
   *        "offset": 0
   *      },
   *      {
   *        "color": "#003ae9",
   *        "opacity": 0,
   *        "offset": 1
   *      }
   *    ]
   *  }
   *}];
   * // for an image fill
   * const imageData = await context.uploadMediaUrl('example', 'https://example.com/image.jpg');
   * shape.fills = [{ fillOpacity: 1, fillImage: imageData }];
   *
   * shape.strokes = [
   *  {
   *    strokeColor: "#2e3434",
   *    strokeStyle: "solid",
   *    strokeWidth: 2,
   *    strokeAlignment: "center",
   *  },
   *];
   * ```
   */
  createEllipse(): Ellipse;
  /**
   * Use this method to create a path. Requires `content:write` permission.
   *
   * @example
   * ```js
   * const path = penpot.createPath();
   * path.name = "My path";
   *
   * // for solid color
   * path.fills = [{ fillColor: "#7EFFF5" }];
   *
   * ```
   */
  createPath(): Path;
  /**
   * Creates a Boolean shape based on the specified boolean operation and shapes. Requires `content:write` permission.
   * @param boolType The type of boolean operation ('union', 'difference', 'exclude', 'intersection').
   * @param shapes An array of shapes to perform the boolean operation on.
   * @return Returns the newly created Boolean shape resulting from the boolean operation.
   *
   * @example
   * ```js
   * const booleanShape = context.createBoolean('union', [shape1, shape2]);
   * ```
   */
  createBoolean(boolType: BooleanType, shapes: Shape[]): Boolean | null;
  /**
   * Creates a Group from an SVG string. Requires `content:write` permission.
   * @param svgString The SVG string representing the shapes to be converted into a group.
   * @return Returns the newly created Group containing the shapes from the SVG.
   *
   * @example
   * ```js
   * const svgGroup = context.createShapeFromSvg('<svg>...</svg>');
   * ```
   */
  createShapeFromSvg(svgString: string): Group | null;
  /**
   * Creates a Group from an SVG string. The SVG can have images and the method returns
   * a Promise because the shape will be available after all images are uploaded.
   * Requires `content:write` permission.
   * @param svgString The SVG string representing the shapes to be converted into a group.
   * @return Returns a promise with the newly created Group containing the shapes from the SVG.
   *
   * @example
   * ```js
   * const svgGroup = await context.createShapeFromSvgWithImages('<svg>...</svg>');
   * ```
   */
  createShapeFromSvgWithImages(svgString: string): Promise<Group | null>;

  /**
   * Creates a Text shape with the specified text content. Requires `content:write` permission.
   * @param text The text content for the Text shape.
   * @return Returns the new created shape, if the shape wasn't created can return null.
   *
   * @example
   * ```js
   * const board = penpot.createBoard();
   * let text;
   * text = penpot.createText();
   * // just change the values like this
   * text.growType = 'auto-height';
   * text.fontFamily = 'Work Sans';
   * text.fontSize = '12';
   * text.fills = [{fillColor: '#9f05ff', fillOpacity: 1}];
   * text.strokes = [{strokeOpacity: 1, strokeStyle: 'solid', strokeWidth: 2, strokeColor: '#deabff', strokeAlignment: 'outer'}];
   * board.appendChild(text);
   * ```
   */
  createText(text: string): Text | null;

  /**
   * Generates markup for the given shapes. Requires `content:read` permission
   * @param shapes
   * @param options
   *
   * @example
   * ```js
   * const markup = context.generateMarkup(shapes, { type: 'html' });
   * console.log(markup);
   * ```
   */
  generateMarkup(shapes: Shape[], options?: { type?: 'html' | 'svg' }): string;

  /**
   * Generates styles for the given shapes. Requires `content:read` permission
   * @param shapes
   * @param options
   *
   * @example
   * ```js
   * const styles = context.generateStyle(shapes, { type: 'css' });
   * console.log(styles);
   * ```
   */
  generateStyle(
    shapes: Shape[],
    options?: {
      type?: 'css';
      withPrelude?: boolean;
      includeChildren?: boolean;
    },
  ): string;

  /**
   * Generates the fontfaces styles necessaries to render the shapes.
   * Requires `content:read` permission
   * @param shapes
   *
   * @example
   * ```js
   * const fontfaces = context.generateFontFaces(penpot.selection);
   * console.log(fontfaces);
   * ```
   */
  generateFontFaces(shapes: Shape[]): Promise<string>;

  /**
   * Adds the current callback as an event listener
   *
   * @example
   * ```js
   * const listenerId = context.addListener('selectionchange', (event) => {
   *   console.log(event);
   * });
   * ```
   */
  addListener<T extends keyof EventsMap>(
    type: T,
    callback: (event: EventsMap[T]) => void,
    props?: { [key: string]: unknown },
  ): symbol;

  /**
   * Removes the listenerId from the list of listeners
   *
   * @example
   * ```js
   * context.removeListener(listenerId);
   * ```
   */
  removeListener(listenerId: symbol): void;

  /**
   * Opens the viewer section. Requires `content:read` permission.
   */
  openViewer(): void;

  /**
   * Creates a new page. Requires `content:write` permission.
   */
  createPage(): Page;

  /**
   * Changes the current open page to given page. Requires `content:read` permission.
   * @param page the page to open
   * @param newWindow if true opens the page in a new window
   *
   * @example
   * ```js
   * context.openPage(page);
   * ```
   */
  openPage(page: Page, newWindow?: boolean): void;

  /**
   * Aligning will move all the selected layers to a position relative to one
   * of them in the horizontal direction.
   * @param shapes to align
   * @param direction where the shapes will be aligned
   */
  alignHorizontal(
    shapes: Shape[],
    direction: 'left' | 'center' | 'right',
  ): void;

  /**
   * Aligning will move all the selected layers to a position relative to one
   * of them in the vertical direction.
   * @param shapes to align
   * @param direction where the shapes will be aligned
   */
  alignVertical(shapes: Shape[], direction: 'top' | 'center' | 'bottom'): void;

  /**
   * Distributing objects to position them  horizontally with equal distances between them.
   * @param shapes to distribute
   */
  distributeHorizontal(shapes: Shape[]): void;

  /**
   * Distributing objects to position them vertically with equal distances between them.
   * @param shapes to distribute
   */
  distributeVertical(shapes: Shape[]): void;

  /**
   * Converts the shapes into Paths. If the shapes are complex will put together
   * all its paths into one.
   * @param shapes to flatten
   */
  flatten(shapes: Shape[]): Path[];
}

/**
 * Utility methods for geometric calculations in Penpot.
 *
 * @example
 * ```js
 * const centerPoint = geometryUtils.center(shapes);
 * console.log(centerPoint);
 * ```
 */
export interface ContextGeometryUtils {
  /**
   * Calculates the center point of a given array of shapes.
   * This method computes the geometric center (centroid) of the bounding boxes of the provided shapes.
   * @param shapes - The array of shapes to calculate the center for.
   * @return Returns the center point as an object with `x` and `y` coordinates, or null if the array is empty.
   *
   * @example
   * ```js
   * const centerPoint = geometryUtils.center(shapes);
   * console.log(centerPoint);
   * ```
   */
  center(shapes: Shape[]): { x: number; y: number } | null;
}

/**
 * Utility methods for determining the types of Penpot shapes.
 *
 * @example
 * ```js
 * const isBoard = typesUtils.isBoard(shape);
 * console.log(isBoard);
 * ```
 */
export interface ContextTypesUtils {
  /**
   * Checks if the given shape is a board.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a board, otherwise false.
   */
  isBoard(shape: Shape): shape is Board;

  /**
   * Checks if the given shape is a group.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a Group, otherwise false.
   */
  isGroup(shape: Shape): shape is Group;

  /**
   * Checks if the given shape is a mask.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a Group (acting as a mask), otherwise false.
   */
  isMask(shape: Shape): shape is Group;

  /**
   * Checks if the given shape is a boolean operation.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a Bool, otherwise false.
   */
  isBool(shape: Shape): shape is Boolean;

  /**
   * Checks if the given shape is a rectangle.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a Rectangle, otherwise false.
   */
  isRectangle(shape: Shape): shape is Rectangle;

  /**
   * Checks if the given shape is a path.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a Path, otherwise false.
   */
  isPath(shape: Shape): shape is Path;

  /**
   * Checks if the given shape is a text element.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a Text, otherwise false.
   */
  isText(shape: Shape): shape is Text;

  /**
   * Checks if the given shape is an ellipse.
   * @param shape - The shape to check.
   * @return Returns true if the shape is an Ellipse, otherwise false.
   */
  isEllipse(shape: Shape): shape is Ellipse;

  /**
   * Checks if the given shape is an SVG.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a SvgRaw, otherwise false.
   */
  isSVG(shape: Shape): shape is SvgRaw;

  /**
   * Checks if the given shape is a variant container.
   * @param shape - The shape to check.
   * @return Returns true if the shape is a variant container, otherwise false.
   */
  isVariantContainer(shape: Shape): shape is VariantContainer;

  /**
   * Checks if the given component is a VariantComponent.
   * @param component - The component to check.
   * @return Returns true if the component is a VariantComponent, otherwise false.
   */
  isVariantComponent(
    component: LibraryComponent,
  ): component is LibraryVariantComponent;
}

/**
 * Utility methods for various operations in Penpot.
 */
export interface ContextUtils {
  /**
   * Geometry utility methods for Penpot.
   * Provides methods for geometric calculations, such as finding the center of a group of shapes.
   *
   * @example
   * ```js
   * const centerPoint = penpot.utils.geometry.center(shapes);
   * console.log(centerPoint);
   * ```
   */
  readonly geometry: ContextGeometryUtils;
  /**
   * Type utility methods for Penpot.
   * Provides methods for determining the types of various shapes in Penpot.
   *
   * @example
   * ```js
   * const isBoard = utils.types.isBoard(shape);
   * console.log(isBoard);
   * ```
   */
  readonly types: ContextTypesUtils;
}

/**
 * Dissolve animation
 */
export interface Dissolve {
  /**
   * Type of the animation
   */
  readonly type: 'dissolve';

  /**
   * Duration of the animation effect
   */
  readonly duration: number;

  /**
   * Function that the dissolve effect will follow for the interpolation.
   * Defaults to `linear`.
   */
  readonly easing?: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
}

/**
 * Represents an ellipse shape in Penpot.
 * This interface extends `ShapeBase` and includes properties specific to ellipses.
 */
export interface Ellipse extends ShapeBase {
  type: 'ellipse';

  /**
   * The fills applied to the shape.
   */
  fills: Fill[];
}

/**
 * Represents a mapping of events to their corresponding types in Penpot.
 * This interface provides information about various events that can be triggered in the application.
 *
 * @example
 * ```js
 * penpot.on('pagechange', (event) => {
 *   console.log(event);
 * });
 * ```
 */
export interface EventsMap {
  /**
   * The `pagechange` event is triggered when the active page in the project is changed.
   */
  pagechange: Page;
  /**
   * The `filechange` event is triggered when there are changes in the current file.
   */
  filechange: File;
  /**
   * The `selectionchange` event is triggered when the selection of elements changes.
   * This event passes a list of identifiers of the selected elements.
   */
  selectionchange: string[];
  /**
   * The `themechange` event is triggered when the application theme is changed.
   */
  themechange: Theme;
  /**
   * The `finish` event is triggered when some operation is finished.
   */
  finish: string;

  /**
   * This event will trigger whenever the shape in the props change. It's mandatory to send
   * with the props an object like `{ shapeId: '<id>' }`
   */
  shapechange: Shape;

  /**
   * The `contentsave` event will trigger when the content file changes.
   */
  contentsave: void;
}

/**
 * Represents export settings in Penpot.
 * This interface includes properties for defining export configurations.
 */
export interface Export {
  /**
   * Type of the file to export. Can be one of the following values: png, jpeg, svg, pdf
   */
  type: 'png' | 'jpeg' | 'svg' | 'pdf';
  /**
   * For bitmap formats represent the scale of the original size to resize the export
   */
  scale?: number;
  /**
   * Suffix that will be appended to the resulting exported file
   */
  suffix?: string;
  /**
   * If true will ignore the children when exporting the shape
   */
  skipChildren?: boolean;
}

/**
 * File represents a file in the Penpot application.
 * It includes properties for the file's identifier, name, and revision number.
 */
export interface File extends PluginData {
  /**
   * The `id` property is a unique identifier for the file.
   */
  readonly id: string;

  /**
   * The `name` for the file
   */
  name: string;

  /**
   * The `revn` will change for every document update
   */
  revn: number;

  /**
   * List all the pages for the current file
   */
  pages: Page[];

  /*
   * Export the current file to an archive.
   * @param `exportType` indicates the type of file to generate.
   * - `'penpot'` will create a *.penpot file with a binary representation of the file
   * - `'zip'` will create a *.zip with the file exported in several SVG files with some JSON metadata
   * @param `libraryExportType` indicates what to do with the linked libraries of the file when
   * exporting it. Defaults to `all` if not sent.
   * - `'all'` will include the libraries as external files that will be exported in a single bundle
   * - `'merge'` will add all the assets into the main file and only one file will be imported
   * - `'detach'` will unlink all the external assets and no libraries will be imported
   * @param `progressCallback` for `zip` export can be pass this callback so a progress report is sent.
   *
   * @example
   * ```js
   * const exportedData = await file.export('penpot', 'all');
   * ```
   */
  export(
    exportType: 'penpot' | 'zip',
    libraryExportType?: 'all' | 'merge' | 'detach',
  ): Promise<Uint8Array>;

  /**
   * Retrieves the versions for the file.
   * @param `criteria.createdBy` retrieves only the versions created by the user `createdBy`.
   * Requires the `content:read` permission.
   */
  findVersions(criteria?: { createdBy: User }): Promise<FileVersion[]>;

  /**
   * Saves the current version into the versions history.
   * Requires the `content:write` permission.
   */
  saveVersion(label: string): Promise<FileVersion>;
}

/**
 * Type defining the file version properties.
 */
export interface FileVersion {
  /**
   * The current label to identify the version.
   */
  label: string;

  /**
   * The user that created the version. If not present, the
   * version is an autosave.
   */
  readonly createdBy?: User;

  /**
   * The date when the version was created.
   */
  readonly createdAt: Date;

  /**
   * If the current version has been generated automatically.
   */
  readonly isAutosave: boolean;

  /*
   * Restores the current version and replaces the content of the active file
   * for the contents of this version.
   * Requires the `content:write` permission.
   * Warning: Calling this will close the plugin because the workspace will reload
   */
  restore(): void;

  /**
   * Remove the current version.
   * Requires the `content:write` permission.
   */
  remove(): Promise<void>;

  /**
   * Converts an autosave version into a permanent version.
   * Requires the `content:write` permission.
   */
  pin(): Promise<FileVersion>;
}

/**
 * Represents fill properties in Penpot. You can add a fill to any shape except for groups.
 * This interface includes properties for defining solid color fills, gradient fills, and image fills.
 */
export interface Fill {
  /**
   * The optional solid fill color, represented as a string (e.g., '#FF5733').
   */
  fillColor?: string;
  /**
   * The optional opacity level of the solid fill color, ranging from 0 (fully transparent) to 1 (fully opaque).
   * Defaults to 1 if omitted.
   */
  fillOpacity?: number;
  /**
   * The optional gradient fill defined by a Gradient object.
   */
  fillColorGradient?: Gradient;
  /**
   * The optional reference to an external file for the fill color.
   */
  fillColorRefFile?: string;
  /**
   * The optional reference ID within the external file for the fill color.
   */
  fillColorRefId?: string;
  /**
   * The optional image fill defined by an ImageData object.
   */
  fillImage?: ImageData;
}

/**
 * This subcontext allows the API o change certain defaults
 */
export interface Flags {
  /**
   * If `true` the .children property will be always sorted in the z-index ordering.
   * Also, appendChild method will be append the children in the top-most position.
   * The insertchild method is changed acordingly to respect this ordering.
   * Defaults to false
   */
  naturalChildOrdering: boolean;
}

/**
 * Represents a flexible layout configuration in Penpot.
 * This interface extends `CommonLayout` and includes properties for defining the direction,
 * wrapping behavior, and child management of a flex layout.
 */
export interface FlexLayout extends CommonLayout {
  /**
   * The direction of the flex layout.
   * - 'row': Main axis is horizontal, from left to right.
   * - 'row-reverse': Main axis is horizontal, from right to left.
   * - 'column': Main axis is vertical, from top to bottom.
   * - 'column-reverse': Main axis is vertical, from bottom to top.
   */
  dir: 'row' | 'row-reverse' | 'column' | 'column-reverse';
  /**
   * The optional wrapping behavior of the flex layout.
   * - 'wrap': Child elements will wrap onto multiple lines.
   * - 'nowrap': Child elements will not wrap.
   */
  wrap?: 'wrap' | 'nowrap';
  /**
   * Appends a child element to the flex layout.
   * @param child The child element to be appended, of type `Shape`.
   *
   * @example
   * ```js
   * flexLayout.appendChild(childShape);
   * ```
   */
  appendChild(child: Shape): void;
}

/**
 * Defines an interaction flow inside penpot. A flow is defined by a starting board for an interaction.
 */
export interface Flow {
  /**
   * The page in which the flow is defined
   */
  readonly page: Page;

  /**
   * The name for the current flow
   */
  name: string;

  /**
   * The starting board for this interaction flow
   */
  startingBoard: Board;

  /**
   * Removes the flow from the page
   */
  remove(): void;
}

/**
 * Represents a font in Penpot, which includes details about the font family, variants, and styling options.
 * This interface provides properties and methods for describing and applying fonts within Penpot.
 */
export interface Font {
  /**
   * This property holds the human-readable name of the font.
   */
  name: string;

  /**
   * The unique identifier of the font.
   */
  fontId: string;

  /**
   * The font family of the font.
   */
  fontFamily: string;

  /**
   * The default font style of the font.
   */
  fontStyle?: 'normal' | 'italic' | null;

  /**
   * The default font variant ID of the font.
   */
  fontVariantId: string;

  /**
   * The default font weight of the font.
   */
  fontWeight: string;

  /**
   * An array of font variants available for the font.
   */
  variants: FontVariant[];

  /**
   * Applies the font styles to a text shape.
   * @param text - The text shape to apply the font styles to.
   * @param variant - Optional. The specific font variant to apply. If not provided, applies the default variant.
   *
   * @example
   * ```js
   * font.applyToText(textShape, fontVariant);
   * ```
   */
  applyToText(text: Text, variant?: FontVariant): void;

  /**
   * Applies the font styles to a text range within a text shape.
   * @param range - The text range to apply the font styles to.
   * @param variant - Optional. The specific font variant to apply. If not provided, applies the default variant.
   *
   * @example
   * ```js
   * font.applyToRange(textRange, fontVariant);
   * ```
   */
  applyToRange(range: TextRange, variant?: FontVariant): void;
}

/**
 * Represents a font variant in Penpot, which defines a specific style variation of a font.
 * This interface provides properties for describing the characteristics of a font variant.
 */
export interface FontVariant {
  /**
   * The name of the font variant.
   */
  name: string;

  /**
   * The unique identifier of the font variant.
   */
  fontVariantId: string;

  /**
   * The font weight of the font variant.
   */
  fontWeight: string;

  /**
   * The font style of the font variant.
   */
  fontStyle: 'normal' | 'italic';
}

/**
 * Represents the context for managing fonts in Penpot.
 * This interface provides methods to interact with fonts, such as retrieving fonts by ID or name.
 */
export interface FontsContext {
  /**
   * An array containing all available fonts.
   */
  all: Font[];

  /**
   * Finds a font by its unique identifier.
   * @param id - The ID of the font to find.
   * @return Returns the `Font` object if found, otherwise `null`.
   *
   * @example
   * ```js
   * const font = fontsContext.findById('font-id');
   * if (font) {
   *   console.log(font.name);
   * }
   * ```
   */
  findById(id: string): Font | null;

  /**
   * Finds a font by its name.
   * @param name - The name of the font to find.
   * @return Returns the `Font` object if found, otherwise `null`.
   *
   * @example
   * ```js
   * const font = fontsContext.findByName('font-name');
   * if (font) {
   *   console.log(font.name);
   * }
   * ```
   */
  findByName(name: string): Font | null;

  /**
   * Finds all fonts matching a specific ID.
   * @param id - The ID to match against.
   * @return Returns an array of `Font` objects matching the provided ID.
   *
   * @example
   * ```js
   * const fonts = fontsContext.findAllById('font-id');
   * console.log(fonts);
   * ```
   */
  findAllById(id: string): Font[];

  /**
   * Finds all fonts matching a specific name.
   * @param name - The name to match against.
   * @return Returns an array of `Font` objects matching the provided name.
   *
   * @example
   * ```js
   * const fonts = fontsContext.findAllByName('font-name');
   * console.log(fonts);
   * ```
   */
  findAllByName(name: string): Font[];
}

/**
 * Represents a gradient configuration in Penpot.
 * A gradient can be either linear or radial and includes properties to define its shape, position, and color stops.
 */
export type Gradient = {
  /**
   * Specifies the type of gradient.
   * - 'linear': A gradient that transitions colors along a straight line.
   * - 'radial': A gradient that transitions colors radiating outward from a central point.
   *
   * @example
   * ```js
   * const gradient: Gradient = { type: 'linear', startX: 0, startY: 0, endX: 100, endY: 100, width: 100, stops: [{ color: '#FF5733', offset: 0 }] };
   * ```
   */
  type: 'linear' | 'radial';
  /**
   * The X-coordinate of the starting point of the gradient.
   */
  startX: number;
  /**
   * The Y-coordinate of the starting point of the gradient.
   */
  startY: number;
  /**
   * The X-coordinate of the ending point of the gradient.
   */
  endX: number;
  /**
   * The Y-coordinate of the ending point of the gradient.
   */
  endY: number;
  /**
   * The width of the gradient. For radial gradients, this could be interpreted as the radius.
   */
  width: number;
  /**
   * An array of color stops that define the gradient.
   */
  stops: Array<{ color: string; opacity?: number; offset: number }>;
};

/**
 * GridLayout represents a grid layout in the Penpot application, extending the common layout interface.
 * It includes properties and methods to manage rows, columns, and child elements within the grid.
 */
export interface GridLayout extends CommonLayout {
  /**
   * The `dir` property specifies the primary direction of the grid layout.
   * It can be either 'column' or 'row'.
   */
  dir: 'column' | 'row';
  /**
   * The `rows` property represents the collection of rows in the grid.
   * This property is read-only.
   */
  readonly rows: Track[];
  /**
   * The `columns` property represents the collection of columns in the grid.
   * This property is read-only.
   */
  readonly columns: Track[];

  /**
   * Adds a new row to the grid.
   * @param type The type of the row to add.
   * @param value The value associated with the row type (optional).
   *
   * @example
   * ```js
   * const board = penpot.createBoard();
   * const grid = board.addGridLayout();
   * grid.addRow("flex", 1);
   * ```
   */
  addRow(type: TrackType, value?: number): void;
  /**
   * Adds a new row to the grid at the specified index.
   * @param index The index at which to add the row.
   * @param type The type of the row to add.
   * @param value The value associated with the row type (optional).
   *
   * @example
   * ```js
   * gridLayout.addRowAtIndex(0, 'fixed', 100);
   * ```
   */
  addRowAtIndex(index: number, type: TrackType, value?: number): void;
  /**
   * Adds a new column to the grid.
   * @param type The type of the column to add.
   * @param value The value associated with the column type (optional).
   *
   * @example
   * ```js
   * const board = penpot.createBoard();
   * const grid = board.addGridLayout();
   * grid.addColumn('percent', 50);
   * ```
   */
  addColumn(type: TrackType, value?: number): void;
  /**
   * Adds a new column to the grid at the specified index.
   * @param index The index at which to add the column.
   * @param type The type of the column to add.
   * @param value The value associated with the column type.
   *
   * @example
   * ```js
   * gridLayout.addColumnAtIndex(1, 'auto');
   * ```
   */
  addColumnAtIndex(index: number, type: TrackType, value: number): void;
  /**
   * Removes a row from the grid at the specified index.
   * @param index The index of the row to remove.
   *
   * @example
   * ```js
   * gridLayout.removeRow(2);
   * ```
   */
  removeRow(index: number): void;
  /**
   * Removes a column from the grid at the specified index.
   * @param index The index of the column to remove.
   *
   * @example
   * ```js
   * gridLayout.removeColumn(3);
   * ```
   */
  removeColumn(index: number): void;
  /**
   * Sets the properties of a column at the specified index.
   * @param index The index of the column to set.
   * @param type The type of the column.
   * @param value The value associated with the column type (optional).
   *
   * @example
   * ```js
   * gridLayout.setColumn(0, 'fixed', 200);
   * ```
   */
  setColumn(index: number, type: TrackType, value?: number): void;
  /**
   * Sets the properties of a row at the specified index.
   * @param index The index of the row to set.
   * @param type The type of the row.
   * @param value The value associated with the row type (optional).
   *
   * @example
   * ```js
   * gridLayout.setRow(1, 'flex');
   * ```
   */
  setRow(index: number, type: TrackType, value?: number): void;

  /**
   * Appends a child element to the grid at the specified row and column.
   * @param child The child element to append.
   * @param row The row index where the child will be placed.
   * @param column The column index where the child will be placed.
   *
   * @example
   * ```js
   * gridLayout.appendChild(childShape, 0, 1);
   * ```
   */
  appendChild(child: Shape, row: number, column: number): void;
}

/**
 * Represents a group of shapes in Penpot.
 * This interface extends `ShapeBase` and includes properties and methods specific to groups.
 */
export interface Group extends ShapeBase {
  /**
   * The type of the shape, which is always 'group' for groups.
   */
  readonly type: 'group';

  // Container Properties
  /**
   * The children shapes contained within the group.
   */
  readonly children: Shape[];
  /**
   * Appends a child shape to the group.
   * @param child The child shape to append.
   *
   * @example
   * ```js
   * group.appendChild(childShape);
   * ```
   */
  appendChild(child: Shape): void;
  /**
   * Inserts a child shape at the specified index within the group.
   * @param index The index at which to insert the child shape.
   * @param child The child shape to insert.
   *
   * @example
   * ```js
   * group.insertChild(0, childShape);
   * ```
   */
  insertChild(index: number, child: Shape): void;

  /**
   * Checks if the group is currently a mask.
   * A mask defines a clipping path for its child shapes.
   */
  isMask(): boolean;

  /**
   * Converts the group into a mask.
   */
  makeMask(): void;
  /**
   * Removes the mask from the group.
   */
  removeMask(): void;
}

/**
 * Represents a board guide in Penpot.
 * This type can be one of several specific board guide types: column, row, or square.
 */
export type Guide = GuideColumn | GuideRow | GuideSquare;

/**
 * Represents a goard guide for columns in Penpot.
 * This interface includes properties for defining the type, visibility, and parameters of column guides within a board.
 */
export interface GuideColumn {
  /**
   * The type of the guide, which is always 'column' for column guides.
   */
  type: 'column';
  /**
   * Specifies whether the column guide is displayed.
   */
  display: boolean;
  /**
   * The parameters defining the appearance and layout of the column guides.
   */
  params: GuideColumnParams;
}

/**
 * Represents parameters for board guide columns in Penpot.
 * This interface includes properties for defining the appearance and layout of column guides within a board.
 */
export interface GuideColumnParams {
  /**
   * The color configuration for the column guides.
   */
  color: { color: string; opacity: number };
  /**
   * The optional alignment type of the column guides.
   * - 'stretch': Columns stretch to fit the available space.
   * - 'left': Columns align to the left.
   * - 'center': Columns align to the center.
   * - 'right': Columns align to the right.
   */
  type?: 'stretch' | 'left' | 'center' | 'right';
  /**
   * The optional size of each column.
   */
  size?: number;
  /**
   * The optional margin between the columns and the board edges.
   */
  margin?: number;
  /**
   * The optional length of each item within the columns.
   */
  itemLength?: number;
  /**
   * The optional gutter width between columns.
   */
  gutter?: number;
}

/**
 * Represents a board guide for rows in Penpot.
 * This interface includes properties for defining the type, visibility, and parameters of row guides within a board.
 */
export interface GuideRow {
  /**
   * The type of the guide, which is always 'row' for row guides.
   */
  type: 'row';
  /**
   * Specifies whether the row guide is displayed.
   */
  display: boolean;
  /**
   * The parameters defining the appearance and layout of the row guides.
   * Note: This reuses the same parameter structure as column guides.
   */
  params: GuideColumnParams;
}

/**
 * Represents a board guide for squares in Penpot.
 * This interface includes properties for defining the type, visibility, and parameters of square guides within a board.
 */
export interface GuideSquare {
  /**
   * The type of the guide, which is always 'square' for square guides.
   */
  type: 'square';
  /**
   * Specifies whether the square guide is displayed.
   */
  display: boolean;
  /**
   * The parameters defining the appearance and layout of the square guides.
   */
  params: GuideSquareParams;
}

/**
 * Represents parameters for board guide squares in Penpot.
 * This interface includes properties for defining the appearance and size of square guides within a board.
 */
export interface GuideSquareParams {
  /**
   * The color configuration for the square guides.
   */
  color: { color: string; opacity: number };
  /**
   * The optional size of each square guide.
   */
  size?: number;
}

/**
 * This object allows to access to some history functions
 */
export interface HistoryContext {
  /**
   * Starts an undo block. All operations done inside this block will be undone together until
   * a call to `undoBlockFinish` is called.
   * @returns the block identifier
   */
  undoBlockBegin(): Symbol;

  /**
   * Ends the undo block started with `undoBlockBegin`
   * @param blockId is the id returned by `undoBlockBegin`
   *
   * @example
   * ```js
   * historyContext.undoBlockFinish(blockId);
   * ```
   */
  undoBlockFinish(blockId: Symbol): void;
}

/**
 * Represents an image shape in Penpot.
 * This interface extends `ShapeBase` and includes properties specific to image shapes.
 */
export interface Image extends ShapeBase {
  type: 'image';

  /**
   * The fills applied to the shape.
   */
  fills: Fill[];
}

/**
 * Represents image data in Penpot.
 * This includes properties for defining the image's dimensions, metadata, and aspect ratio handling.
 */
export type ImageData = {
  /**
   * The optional name of the image.
   */
  name?: string;
  /**
   * The width of the image.
   */
  width: number;
  /**
   * The height of the image.
   */
  height: number;
  /**
   * The optional media type of the image (e.g., 'image/png', 'image/jpeg').
   */
  mtype?: string;
  /**
   * The unique identifier for the image.
   */
  id: string;
  /**
   * Whether to keep the aspect ratio of the image when resizing.
   * Defaults to false if omitted.
   */
  keepAspectRatio?: boolean;

  /**
   * Returns the imaged data as a byte array.
   */
  data(): Promise<Uint8Array>;
};

/**
 * Penpot allows you to prototype interactions by connecting boards, which can act as screens.
 */
export interface Interaction {
  /**
   * The shape that owns the interaction
   */
  readonly shape?: Shape;

  /**
   * The user action that will start the interaction.
   */
  trigger: Trigger;

  /**
   * Time in **milliseconds** after the action will happen. Only applies to `after-delay` triggers.
   */
  delay?: number | null;

  /**
   * The action that will execute after the trigger happens.
   */
  action: Action;

  /**
   * Removes the interaction
   */
  remove(): void;
}

/**
 * Properties for defining the layout of a cell in Penpot.
 */
export interface LayoutCellProperties {
  /**
   * The row index of the cell.
   * This value is optional and indicates the starting row of the cell.
   */
  row?: number;

  /**
   * The number of rows the cell should span.
   * This value is optional and determines the vertical span of the cell.
   */
  rowSpan?: number;

  /**
   * The column index of the cell.
   * This value is optional and indicates the starting column of the cell.
   */
  column?: number;

  /**
   * The number of columns the cell should span.
   * This value is optional and determines the horizontal span of the cell.
   */
  columnSpan?: number;

  /**
   * The name of the grid area that this cell belongs to.
   * This value is optional and can be used to define named grid areas.
   */
  areaName?: string;

  /**
   * The positioning mode of the cell.
   * This value can be 'auto', 'manual', or 'area' and determines how the cell is positioned within the layout.
   */
  position?: 'auto' | 'manual' | 'area';
}

/**
 * Properties for defining the layout of a child element in Penpot.
 */
export interface LayoutChildProperties {
  /**
   * Specifies whether the child element is positioned absolutely.
   * When set to true, the element is taken out of the normal document flow and positioned relative to its nearest positioned ancestor.
   */
  absolute: boolean;

  /**
   * Defines the stack order of the child element
   * Elements with a higher zIndex will be displayed in front of those with a lower zIndex.
   */
  zIndex: number;

  /**
   * Determines the horizontal sizing behavior of the child element
   * - 'auto': The width is determined by the content.
   * - 'fill': The element takes up the available width.
   * - 'fix': The width is fixed.
   */
  horizontalSizing: 'auto' | 'fill' | 'fix';

  /**
   * Determines the vertical sizing behavior of the child element.
   * - 'auto': The height is determined by the content.
   * - 'fill': The element takes up the available height.
   * - 'fix': The height is fixed.
   */
  verticalSizing: 'auto' | 'fill' | 'fix';

  /**
   * Aligns the child element within its container.
   * - 'auto': Default alignment.
   * - 'start': Aligns the element at the start of the container.
   * - 'center': Centers the element within the container.
   * - 'end': Aligns the element at the end of the container.
   * - 'stretch': Stretches the element to fill the container.
   */
  alignSelf: 'auto' | 'start' | 'center' | 'end' | 'stretch';

  /**
   * Sets the horizontal margin of the child element.
   * This is the space on the left and right sides of the element.
   */
  horizontalMargin: number;

  /**
   * Sets the vertical margin of the child element.
   * This is the space on the top and bottom sides of the element.
   */
  verticalMargin: number;

  /**
   * Sets the top margin of the child element.
   * This is the space above the element.
   */
  topMargin: number;

  /**
   * Sets the right margin of the child element.
   * This is the space to the right of the element.
   */
  rightMargin: number;

  /**
   * Sets the bottom margin of the child element.
   * This is the space below the element.
   */
  bottomMargin: number;

  /**
   * Sets the left margin of the child element.
   * This is the space to the left of the element.
   */
  leftMargin: number;

  /**
   * Defines the maximum width of the child element.
   * If set to null, there is no maximum width constraint.
   */
  maxWidth: number | null;

  /**
   * Defines the maximum height of the child element.
   * If set to null, there is no maximum height constraint.
   */
  maxHeight: number | null;
  /**
   * Defines the minimum width of the child element.
   * If set to null, there is no minimum width constraint.
   */
  minWidth: number | null;

  /**
   * Defines the minimum height of the child element.
   * If set to null, there is no minimum height constraint.
   */
  minHeight: number | null;
}

/**
 * Represents a library in Penpot, containing colors, typographies, and components.
 */
export interface Library extends PluginData {
  /**
   * The unique identifier of the library.
   */
  readonly id: string;

  /**
   * The name of the library.
   */
  readonly name: string;

  /**
   * An array of color elements in the library.
   * @example
   * ```js
   * console.log(penpot.library.local.colors);
   * ```
   */
  readonly colors: LibraryColor[];

  /**
   * An array of typography elements in the library.
   */
  readonly typographies: LibraryTypography[];

  /**
   * An array of component elements in the library.
   * @example
   * ```js
   * console.log(penpot.library.local.components);
   */
  readonly components: LibraryComponent[];

  /**
   * A catalog of Design Tokens in the library.
   *
   * See `TokenCatalog` type to see usage.
   */
  readonly tokens: TokenCatalog;

  /**
   * Creates a new color element in the library.
   * @return Returns a new `LibraryColor` object representing the created color element.
   *
   * @example
   * ```js
   * const newColor = penpot.library.local.createColor();
   * console.log(newColor);
   * ```
   */
  createColor(): LibraryColor;

  /**
   * Creates a new typography element in the library.
   * @return Returns a new `LibraryTypography` object representing the created typography element.
   *
   * @example
   * ```js
   * const newTypography = library.createTypography();
   * ```
   */
  createTypography(): LibraryTypography;

  /**
   * Creates a new component element in the library using the provided shapes.
   * @param shapes An array of `Shape` objects representing the shapes to be included in the component.
   * @return Returns a new `LibraryComponent` object representing the created component element.
   *
   * @example
   * ```js
   * const newComponent = penpot.library.local.createComponent([shape1, shape2]);
   * ```
   */
  createComponent(shapes: Shape[]): LibraryComponent;
}

/**
 * Represents a color element from a library in Penpot.
 * This interface extends `LibraryElement` and includes properties specific to color elements.
 */
export interface LibraryColor extends LibraryElement {
  /**
   * The color value of the library color.
   */
  color?: string;

  /**
   * The opacity value of the library color.
   */
  opacity?: number;

  /**
   * The gradient value of the library color, if it's a gradient.
   */
  gradient?: Gradient;

  /**
   * The image data of the library color, if it's an image fill.
   */
  image?: ImageData;

  /**
   * Converts the library color into a fill object.
   * @return Returns a `Fill` object representing the color as a fill.
   *
   * @example
   * ```js
   * const fill = libraryColor.asFill();
   * ```
   */
  asFill(): Fill;
  /**
   * Converts the library color into a stroke object.
   * @return Returns a `Stroke` object representing the color as a stroke.
   *
   * @example
   * ```js
   * const stroke = libraryColor.asStroke();
   * ```
   */
  asStroke(): Stroke;
}

/**
 * Represents a component element from a library in Penpot.
 * This interface extends `LibraryElement` and includes properties specific to component elements.
 */
export interface LibraryComponent extends LibraryElement {
  /**
   * Creates an instance of the component.
   * @return Returns a `Shape` object representing the instance of the component.
   *
   * @example
   * ```js
   * const componentInstance = libraryComponent.instance();
   * ```
   */
  instance(): Shape;

  /**
   * @return Returns the reference to the main component shape.
   */
  mainInstance(): Shape;

  /**
   * @return true when this component is a VariantComponent
   */
  isVariant(): boolean;

  /**
   * Creates a new Variant from this standard Component. It creates a VariantContainer, transform this Component into a VariantComponent, duplicates it, and creates a
   * set of properties based on the component name and path.
   * Similar to doing it with the contextual menu or the shortcut on the Penpot interface
   */
  transformInVariant(): void;
}

/**
 * Represents a component element from a library in Penpot.
 * This interface extends `LibraryElement` and includes properties specific to component elements.
 */
export interface LibraryVariantComponent extends LibraryComponent {
  /**
   * Access to the Variant interface, for attributes and actions over the full Variant (not only this VariantComponent)
   */
  readonly variants: Variants | null;

  /**
   * A list of the variants props of this VariantComponent. Each property have a key and a value
   */
  readonly variantProps: { [property: string]: string };

  /**
   * If this VariantComponent has an invalid name, that does't follow the structure [property]=[value], [property]=[value]
   * this field stores that invalid name
   */
  variantError: string;

  /**
   * Creates a duplicate of the current VariantComponent on its Variant
   */
  addVariant(): void;

  /**
   * Sets the value of the variant property on the indicated position
   */

  setVariantProperty(pos: number, value: string): void;
}

/**
 * Represents the context of Penpot libraries, including both local and connected libraries.
 * This type contains references to the local library and an array of connected libraries.
 */
export type LibraryContext = {
  /**
   * The local library in the Penpot context.
   *
   * @example
   * ```js
   * const localLibrary = libraryContext.local;
   * ```
   */
  readonly local: Library;

  /**
   * An array of connected libraries in the Penpot context.
   *
   * @example
   * ```js
   * const connectedLibraries = libraryContext.connected;
   * ```
   */
  readonly connected: Library[];

  /**
   * Retrieves a summary of available libraries that can be connected to.
   * @return Returns a promise that resolves to an array of `LibrarySummary` objects representing available libraries.
   *
   * @example
   * ```js
   * const availableLibraries = await libraryContext.availableLibraries();
   * ```
   */
  availableLibraries(): Promise<LibrarySummary[]>;

  /**
   * Connects to a specific library identified by its ID.
   * @return Returns a promise that resolves to the `Library` object representing the connected library.
   * @param libraryId - The ID of the library to connect to.
   *
   * @example
   * ```js
   * const connectedLibrary = await libraryContext.connectLibrary('library-id');
   * ```
   */
  connectLibrary(libraryId: string): Promise<Library>;
};

/**
 * Represents an element in a Penpot library.
 * This interface provides information about a specific element in a library.
 */
export interface LibraryElement extends PluginData {
  /**
   * The unique identifier of the library element.
   */
  readonly id: string;

  /**
   * The unique identifier of the library to which the element belongs.
   */
  readonly libraryId: string;

  /**
   * The name of the library element.
   */
  name: string;

  /**
   * The path of the library element.
   */
  path: string;
}

/**
 * Represents a summary of a Penpot library.
 * This interface provides properties for summarizing various aspects of a Penpot library.
 */
export interface LibrarySummary {
  /**
   * The unique identifier of the library.
   */
  readonly id: string;

  /**
   * The name of the library.
   */
  readonly name: string;

  /**
   * The number of colors in the library.
   */
  readonly numColors: number;

  /**
   * The number of components in the library.
   */
  readonly numComponents: number;

  /**
   * The number of typographies in the library.
   */
  readonly numTypographies: number;
}

/**
 * Represents a typography element from a library in Penpot.
 * This interface extends `LibraryElement` and includes properties specific to typography elements.
 */
export interface LibraryTypography extends LibraryElement {
  /**
   * The unique identifier of the font used in the typography element.
   */
  fontId: string;

  /**
   * The font families of the typography element.
   */
  fontFamilies: string;

  /**
   * The unique identifier of the font variant used in the typography element.
   */
  fontVariantId: string;

  /**
   * The font size of the typography element.
   */
  fontSize: string;

  /**
   * The font weight of the typography element.
   */
  fontWeight: string;

  /**
   * The font style of the typography element.
   */
  fontStyle?: 'normal' | 'italic' | null;

  /**
   * The line height of the typography element.
   */
  lineHeight: string;

  /**
   * The letter spacing of the typography element.
   */
  letterSpacing: string;

  /**
   * The text transform applied to the typography element.
   */
  textTransform?: 'uppercase' | 'capitalize' | 'lowercase' | null;

  /**
   * Applies the typography styles to a text shape.
   * @param shape The text shape to apply the typography styles to.
   *
   * @example
   * ```js
   * typographyElement.applyToText(textShape);
   * ```
   */
  applyToText(shape: Shape): void;

  /**
   * Applies the typography styles to a range of text within a text shape.
   * @param range Represents a range of text within a Text shape. This interface provides properties for styling and formatting text ranges.
   *
   * @example
   * ```js
   * typographyElement.applyToTextRange(textShape);
   * ```
   */
  applyToTextRange(range: TextRange): void;

  /**
   * Sets the font and optionally its variant for the typography element.
   * @param font - The font to set.
   * @param variant - The font variant to set (optional).
   *
   * @example
   * ```js
   * typographyElement.setFont(newFont, newVariant);
   * ```
   */
  setFont(font: Font, variant?: FontVariant): void;
}

/**
 * Proxy for the local storage. Only elements owned by the plugin
 * can be stored and accessed.
 * Warning: other plugins won't be able to access this information but
 * the user could potentialy access the data through the browser information.
 */
export interface LocalStorage {
  /**
   * Retrieve the element with the given key
   * Requires the `allow:localstorage` permission.
   */
  getItem(key: string): string;

  /**
   * Set the data given the key. If the value already existed it
   * will be overriden. The value will be stored in a string representation.
   * Requires the `allow:localstorage` permission.
   */
  setItem(key: string, value: unknown): void;

  /**
   * Remove the value stored in the key.
   * Requires the `allow:localstorage` permission.
   */
  removeItem(key: string): void;

  /**
   * Return all the keys for the data stored by the plugin.
   * Requires the `allow:localstorage` permission.
   */
  getKeys(): string[];
}

/**
 * It takes the user from one board to the destination set in the interaction.
 */
export interface NavigateTo {
  /**
   * Type of action
   */
  readonly type: 'navigate-to';

  /**
   * Board to which the action targets
   */
  readonly destination: Board;

  /**
   * When true the scroll will be preserved.
   */
  readonly preserveScrollPosition?: boolean;

  /**
   * Animation displayed with this interaction.
   */
  readonly animation?: Animation;
}

/**
 * It opens a board right over the current board.
 */
export interface OpenOverlay extends OverlayAction {
  /**
   * The action type
   */
  readonly type: 'open-overlay';
}

/**
 * This action opens an URL in a new tab.
 */
export interface OpenUrl {
  /**
   * The action type
   */
  readonly type: 'open-url';
  /**
   * The URL to open when the action is executed
   */
  readonly url: string;
}

/**
 * Base type for the actions "open-overlay" and "toggle-overlay" that share most of their properties
 */
export interface OverlayAction {
  /**
   * Overlay board that will be opened.
   */
  readonly destination: Board;

  /**
   * Base shape to which the overlay will be positioned taking constraints into account.
   */
  readonly relativeTo?: Shape;

  /**
   * Positioning of the overlay.
   */
  readonly position?:
    | 'manual'
    | 'center'
    | 'top-left'
    | 'top-right'
    | 'top-center'
    | 'bottom-left'
    | 'bottom-right'
    | 'bottom-center';

  /**
   * For `position = 'manual'` the location of the overlay.
   */
  readonly manualPositionLocation?: Point;

  /**
   * When true the overlay will be closed when clicking outside
   */
  readonly closeWhenClickOutside?: boolean;

  /**
   * When true a background will be added to the overlay.
   */
  readonly addBackgroundOverlay?: boolean;

  /**
   * Animation displayed with this interaction.
   */
  readonly animation?: Animation;
}

/**
 * Page represents a page in the Penpot application.
 * It includes properties for the page's identifier and name, as well as methods for managing shapes on the page.
 */
export interface Page extends PluginData {
  /**
   * The `id` property is a unique identifier for the page.
   */
  readonly id: string;
  /**
   * The `name` property is the name of the page.
   */
  name: string;

  /**
   * The ruler guides attached to the board
   */
  readonly rulerGuides: RulerGuide[];

  /**
   * The root shape of the current page. Will be the parent shape of all the shapes inside the document.
   * Requires `content:read` permission.
   */
  root: Shape;

  /**
   * Retrieves a shape by its unique identifier.
   * @param id The unique identifier of the shape.
   *
   * @example
   * ```js
   * const shape = penpot.currentPage.getShapeById('shapeId');
   * ```
   */
  getShapeById(id: string): Shape | null;

  /**
   * Finds all shapes on the page.
   * Optionaly it gets a criteria object to search for specific criteria
   * @param criteria
   * @example
   * ```js
   * const shapes = penpot.currentPage.findShapes({ name: 'exampleName' });
   * ```
   */
  findShapes(criteria?: {
    name?: string;
    nameLike?: string;
    type?:
      | 'board'
      | 'group'
      | 'boolean'
      | 'rectangle'
      | 'path'
      | 'text'
      | 'ellipse'
      | 'svg-raw'
      | 'image';
  }): Shape[];

  /**
   * The interaction flows defined for the page.
   */
  readonly flows: Flow[];

  /**
   * Creates a new flow in the page.
   * @param name the name identifying the flow
   * @param board the starting board for the current flow
   *
   * @example
   * ```js
   * const flow = penpot.currentPage.createFlow('exampleFlow', board);
   * ```
   */
  createFlow(name: string, board: Board): Flow;

  /**
   * Removes the flow from the page
   * @param flow the flow to be removed from the page
   */
  removeFlow(flow: Flow): void;

  /**
   * Creates a new ruler guide.
   */
  addRulerGuide(
    orientation: RulerGuideOrientation,
    value: number,
    board?: Board,
  ): RulerGuide;

  /**
   * Removes the `guide` from the current page.
   */
  removeRulerGuide(guide: RulerGuide): void;

  /**
   * Creates a new comment thread in the `position`. Optionaly adds
   * it into the `board`.
   * Returns the thread created.
   * Requires the `comment:write` permission.
   */
  addCommentThread(content: string, position: Point): Promise<CommentThread>;

  /**
   * Removes the comment thread.
   * Requires the `comment:write` permission.
   */
  removeCommentThread(commentThread: CommentThread): Promise<void>;

  /**
   * Find all the comments that match the criteria.
   * - `onlyYours`: if `true` will return the threads where the current
   *                user has engaged.
   * - `showResolved`: by default resolved comments will be hidden. If `true`
   *                   the resolved will be returned.
   * Requires the `comment:read` or `comment:write` permission.
   */
  findCommentThreads(criteria?: {
    onlyYours: boolean;
    showResolved: boolean;
  }): Promise<CommentThread[]>;
}

/**
 * Represents a path shape in Penpot.
 * This interface extends `ShapeBase` and includes properties and methods specific to paths.
 */
export interface Path extends ShapeBase {
  /**
   * The type of the shape, which is always 'path' for path shapes.
   */
  readonly type: 'path';
  /**
   * Converts the path shape to its path data representation.
   * @return Returns the path data (d attribute) as a string.
   * @deprecated Use the `d` attribute
   */
  toD(): string;

  /**
   * The content of the boolean shape, defined as the path string.
   * @deprecated Use either `d` or `commands`.
   */
  content: string;

  /**
   * The content of the boolean shape, defined as the path string.
   */
  d: string;

  /**
   * The content of the boolean shape, defined as an array of path commands.
   */
  commands: Array<PathCommand>;

  /**
   * The fills applied to the shape.
   */
  fills: Fill[];
}

/**
 * Represents a path command in Penpot.
 * This interface includes a property for defining the type of command.
 */
interface PathCommand {
  /**
   * The type of path command.
   * Possible values include:
   * - 'M' or 'move-to': Move to a new point.
   * - 'Z' or 'close-path': Close the current path.
   * - 'L' or 'line-to': Draw a straight line to a new point.
   * - 'H' or 'line-to-horizontal': Draw a horizontal line to a new point.
   * - 'V' or 'line-to-vertical': Draw a vertical line to a new point.
   * - 'C' or 'curve-to': Draw a cubic Bezier curve to a new point.
   * - 'S' or 'smooth-curve-to': Draw a smooth cubic Bezier curve to a new point.
   * - 'Q' or 'quadratic-bezier-curve-to': Draw a quadratic Bezier curve to a new point.
   * - 'T' or 'smooth-quadratic-bezier-curve-to': Draw a smooth quadratic Bezier curve to a new point.
   * - 'A' or 'elliptical-arc': Draw an elliptical arc to a new point.
   *
   * @example
   * ```js
   * const pathCommand: PathCommand = { command: 'M', params: { x: 0, y: 0 } };
   * ```
   */
  command:
    | 'M'
    | 'move-to'
    | 'Z'
    | 'close-path'
    | 'L'
    | 'line-to'
    | 'H'
    | 'line-to-horizontal'
    | 'V'
    | 'line-to-vertical'
    | 'C'
    | 'curve-to'
    | 'S'
    | 'smooth-curve-to'
    | 'Q'
    | 'quadratic-bezier-curve-to'
    | 'T'
    | 'smooth-quadratic-bezier-curve-to'
    | 'A'
    | 'elliptical-arc';

  /**
   * Optional parameters associated with the path command.
   */
  params?: {
    /**
     * The x-coordinate of the point (or endpoint).
     */
    x?: number;

    /**
     * The y-coordinate of the point (or endpoint).
     */
    y?: number;

    /**
     * The x-coordinate of the first control point for curves.
     */
    c1x?: number;

    /**
     * The y-coordinate of the first control point for curves.
     */
    c1y?: number;

    /**
     * The x-coordinate of the second control point for curves.
     */
    c2x?: number;

    /**
     * The y-coordinate of the second control point for curves.
     */
    c2y?: number;

    /**
     * The radius of the ellipse's x-axis.
     */
    rx?: number;

    /**
     * The radius of the ellipse's y-axis.
     */
    ry?: number;

    /**
     * The rotation angle of the ellipse's x-axis.
     */
    xAxisRotation?: number;

    /**
     * A flag indicating whether to use the larger arc.
     */
    largeArcFlag?: boolean;

    /**
     * A flag indicating the direction of the arc.
     */
    sweepFlag?: boolean;
  };
}

/**
 * Provides methods for managing plugin-specific data associated with a Penpot shape.
 */
export interface PluginData {
  /**
   * Retrieves the data for our own plugin, given a specific key.
   *
   * @param key The key for which to retrieve the data.
   * @return Returns the data associated with the key as a string.
   *
   * @example
   * ```js
   * const data = shape.getPluginData('exampleKey');
   * console.log(data);
   * ```
   */
  getPluginData(key: string): string;

  /**
   * Sets the plugin-specific data for the given key.
   *
   * @param key The key for which to set the data.
   * @param value The data to set for the key.
   *
   * @example
   * ```js
   * shape.setPluginData('exampleKey', 'exampleValue');
   * ```
   */
  setPluginData(key: string, value: string): void;

  /**
   * Retrieves all the keys for the plugin-specific data.
   *
   * @return Returns an array of strings representing all the keys.
   *
   * @example
   * ```js
   * const keys = shape.getPluginDataKeys();
   * console.log(keys);
   * ```
   */
  getPluginDataKeys(): string[];

  /**
   * If we know the namespace of an external plugin, this is the way to get their data.
   *
   * @param namespace The namespace for the shared data.
   * @param key The key for which to retrieve the data.
   * @return Returns the shared data associated with the key as a string.
   *
   * @example
   * ```js
   * const sharedData = shape.getSharedPluginData('exampleNamespace', 'exampleKey');
   * console.log(sharedData);
   * ```
   */
  getSharedPluginData(namespace: string, key: string): string;

  /**
   * Sets the shared plugin-specific data for the given namespace and key.
   *
   * @param namespace The namespace for the shared data.
   * @param key The key for which to set the data.
   * @param value The data to set for the key.
   *
   * @example
   * ```js
   * shape.setSharedPluginData('exampleNamespace', 'exampleKey', 'exampleValue');
   * ```
   */
  setSharedPluginData(namespace: string, key: string, value: string): void;

  /**
   * Retrieves all the keys for the shared plugin-specific data in the given namespace.
   *
   * @param namespace The namespace for the shared data.
   * @return Returns an array of strings representing all the keys in the namespace.
   *
   * @example
   * ```js
   * const sharedKeys = shape.getSharedPluginDataKeys('exampleNamespace');
   * console.log(sharedKeys);
   * ```
   */
  getSharedPluginDataKeys(namespace: string): string[];
}

/**
 * Point represents a point in 2D space, typically with x and y coordinates.
 */
export type Point = { x: number; y: number };

/**
 * It takes back to the last board shown.
 */
export interface PreviousScreen {
  /**
   * The action type
   */
  readonly type: 'previous-screen';
}

/**
 * Push animation
 */
export interface Push {
  /**
   * Type of the animation
   */
  readonly type: 'push';

  /**
   * Direction for the push animation
   */
  readonly direction: 'right' | 'left' | 'up' | 'down';

  /**
   * Duration of the animation effect
   */
  readonly duration: number;

  /**
   * Function that the dissolve effect will follow for the interpolation.
   * Defaults to `linear`
   */
  readonly easing?: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
}

/**
 * Represents a rectangle shape in Penpot.
 * This interface extends `ShapeBase` and includes properties specific to rectangles.
 */
export interface Rectangle extends ShapeBase {
  /**
   * The type of the shape, which is always 'rect' for rectangle shapes.
   */
  readonly type: 'rectangle';

  /**
   * The fills applied to the shape.
   */
  fills: Fill[];
}

/**
 * Represents a ruler guide. These are horizontal or vertical lines that can be
 * used to position elements in the UI.
 */
export interface RulerGuide {
  /**
   * `orientation` indicates whether the ruler is either `horizontal` or `vertical`
   */
  readonly orientation: RulerGuideOrientation;

  /**
   * `position` is the position in the axis in absolute positioning. If this is a board
   * guide will return the positioning relative to the board.
   */
  position: number;

  /**
   * If the guide is attached to a board this will retrieve the board shape
   */
  board?: Board;
}

/**
 *
 */
export type RulerGuideOrientation = 'horizontal' | 'vertical';

/**
 * Represents shadow properties in Penpot.
 * This interface includes properties for defining drop shadows and inner shadows, along with their visual attributes.
 */
export interface Shadow {
  /**
   * The optional unique identifier for the shadow.
   */
  id?: string;
  /**
   * The optional style of the shadow.
   * - 'drop-shadow': A shadow cast outside the element.
   * - 'inner-shadow': A shadow cast inside the element.
   */
  style?: 'drop-shadow' | 'inner-shadow';
  /**
   * The optional X-axis offset of the shadow.
   */
  offsetX?: number;
  /**
   * The optional Y-axis offset of the shadow.
   */
  offsetY?: number;
  /**
   * The optional blur radius of the shadow.
   */
  blur?: number;
  /**
   * The optional spread radius of the shadow.
   */
  spread?: number;
  /**
   * Specifies whether the shadow is hidden.
   * Defaults to false if omitted.
   */
  hidden?: boolean;
  /**
   * The optional color of the shadow, defined by a Color object.
   */
  color?: Color;
}

/**
 * Shape represents a union of various shape types used in the Penpot project.
 * This type allows for different shapes to be handled under a single type umbrella.
 *
 * @example
 * ```js
 * let shape: Shape;
 * if (penpot.utils.types.isRectangle(shape)) {
 *   console.log(shape.type);
 * }
 * ```
 */
export type Shape =
  | Board
  | Group
  | Boolean
  | Rectangle
  | Path
  | Text
  | Ellipse
  | SvgRaw
  | Image;

/**
 * Represents the base properties and methods of a shape in Penpot.
 * This interface provides common properties and methods shared by all shapes.
 */
export interface ShapeBase extends PluginData {
  /**
   * The unique identifier of the shape.
   */
  readonly id: string;

  /**
   * The name of the shape.
   */
  name: string;

  /**
   * The parent shape. If the shape is the first level the parent will be the root shape.
   * For the root shape the parent is null
   */
  readonly parent: Shape | null;

  /**
   * Returns the index of the current shape in the parent
   */
  readonly parentIndex: number;

  /**
   * The x-coordinate of the shape's position.
   */
  x: number;

  /**
   * The y-coordinate of the shape's position.
   */
  y: number;

  /**
   * The width of the shape.
   */
  readonly width: number;

  /**
   * The height of the shape.
   */
  readonly height: number;

  /**
   * @return Returns the bounding box surrounding the current shape
   */
  readonly bounds: Bounds;

  /**
   * @return Returns the geometric center of the shape
   */
  readonly center: Point;

  /**
   * Indicates whether the shape is blocked.
   */
  blocked: boolean;

  /**
   * Indicates whether the shape is hidden.
   */
  hidden: boolean;

  /**
   * Indicates whether the shape is visible.
   */
  visible: boolean;

  /**
   * Indicates whether the shape has proportion lock enabled.
   */
  proportionLock: boolean;

  /**
   * The horizontal constraints applied to the shape.
   */
  constraintsHorizontal: 'left' | 'right' | 'leftright' | 'center' | 'scale';

  /**
   * The vertical constraints applied to the shape.
   */
  constraintsVertical: 'top' | 'bottom' | 'topbottom' | 'center' | 'scale';

  /**
   * The border radius of the shape.
   */
  borderRadius: number;

  /**
   * The border radius of the top-left corner of the shape.
   */
  borderRadiusTopLeft: number;

  /**
   * The border radius of the top-right corner of the shape.
   */
  borderRadiusTopRight: number;

  /**
   * The border radius of the bottom-right corner of the shape.
   */
  borderRadiusBottomRight: number;

  /**
   * The border radius of the bottom-left corner of the shape.
   */
  borderRadiusBottomLeft: number;

  /**
   * The opacity of the shape.
   */
  opacity: number;

  /**
   * The blend mode applied to the shape.
   */
  blendMode:
    | 'normal'
    | 'darken'
    | 'multiply'
    | 'color-burn'
    | 'lighten'
    | 'screen'
    | 'color-dodge'
    | 'overlay'
    | 'soft-light'
    | 'hard-light'
    | 'difference'
    | 'exclusion'
    | 'hue'
    | 'saturation'
    | 'color'
    | 'luminosity';

  /**
   * The shadows applied to the shape.
   */
  shadows: Shadow[];

  /**
   * The blur effect applied to the shape.
   */
  blur?: Blur;

  /**
   * The export settings of the shape.
   */
  exports: Export[];

  /**
   * The x-coordinate of the shape relative to its board.
   */
  boardX: number;

  /**
   * The y-coordinate of the shape relative to its board.
   */
  boardY: number;

  /**
   * The x-coordinate of the shape relative to its parent.
   */
  parentX: number;

  /**
   * The y-coordinate of the shape relative to its parent.
   */
  parentY: number;

  /**
   * Indicates whether the shape is flipped horizontally.
   */
  flipX: boolean;

  /**
   * Indicates whether the shape is flipped vertically.
   */
  flipY: boolean;

  /**
   * @return Returns the rotation in degrees of the shape with respect to it's center.
   */
  rotation: number;

  /**
   * The fills applied to the shape.
   */
  fills: Fill[] | 'mixed';

  /**
   * The strokes applied to the shape.
   */
  strokes: Stroke[];

  /**
   * Layout properties for children of the shape.
   */
  readonly layoutChild?: LayoutChildProperties;

  /**
   * Layout properties for cells in a grid layout.
   */
  readonly layoutCell?: LayoutChildProperties;

  /**
   * Changes the index inside the parent of the current shape.
   * This method will shift the indexes of the shapes around that position to
   * match the index.
   * If the index is greater than the number of elements it will positioned last.
   *
   * @param index the new index for the shape to be in
   */
  setParentIndex(index: number): void;

  /**
   * The design tokens applied to this shape.
   * It's a map property name -> token name.
   *
   * NOTE that the tokens application is by name and not by id. If there exist
   * several tokens with the same name in different sets, the actual token applied
   * and the value set to the attributes will depend on which sets are active
   * (and will change if different sets or themes are activated later).
   */
  readonly tokens: { [property: string]: string };

  /**
   * @return Returns true if the current shape is inside a component instance
   */
  isComponentInstance(): boolean;

  /**
   * @return Returns true if the current shape is inside a component **main** instance
   */
  isComponentMainInstance(): boolean;

  /**
   * @return Returns true if the current shape is inside a component **copy** instance
   */
  isComponentCopyInstance(): boolean;

  /**
   * @return Returns true when the current shape is the root of a component tree
   */
  isComponentRoot(): boolean;

  /**
   * @return Returns true when the current shape is the head of a components tree nested structure
   */
  isComponentHead(): boolean;

  /**
   * @return Returns the equivalent shape in the component main instance. If the current shape is inside a
   * main instance will return `null`;
   */
  componentRefShape(): Shape | null;

  /**
   * @return Returns the root of the component tree structure for the current shape. If the current shape
   * is already a root will return itself.
   */
  componentRoot(): Shape | null;

  /**
   * @return Returns the head of the component tree structure for the current shape. If the current shape
   * is already a head will return itself.
   */
  componentHead(): Shape | null;

  /**
   * @return If the shape is a component instance, returns the reference to the component associated
   * otherwise will return null
   */
  component(): LibraryComponent | null;

  /**
   * If the current shape is a component it will remove the component information and leave the
   * shape as a "basic shape"
   */
  detach(): void;

  /**
   * TODO
   */
  swapComponent(component: LibraryComponent): void;

  /**
   * Switch a VariantComponent copy to the nearest one that has the specified property value
   * @param pos The position of the poroperty to update
   * @param value The new value of the property
   */
  switchVariant(pos: number, value: string): void;

  /**
   * Combine several standard Components into a VariantComponent. Similar to doing it with the contextual menu
   * on the Penpot interface.
   * The current shape must be a component main instance.
   * @param ids A list of ids of the main instances of the components to combine with this one.
   */
  combineAsVariants(ids: string[]): void;

  /**
   * @return Returns true when the current shape is the head of a components tree nested structure,
   * and that component is a VariantComponent
   */
  isVariantHead(): boolean;

  /**
   * Resizes the shape to the specified width and height.
   * @param width The new width of the shape.
   * @param height The new height of the shape.
   *
   * @example
   * ```js
   * shape.resize(200, 100);
   * ```
   */
  resize(width: number, height: number): void;

  /**
   * Rotates the shape in relation with the given center.
   * @param angle Angle in degrees to rotate.
   * @param center Center of the transform rotation. If not send will use the geometri center of the shapes.
   *
   * @example
   * ```js
   * shape.rotate(45);
   * ```
   */
  rotate(angle: number, center?: { x: number; y: number } | null): void;

  /**
   * Moves the current shape to the front of its siblings
   */
  bringToFront(): void;

  /**
   * Moves the current shape one position forward in its list of siblings
   */
  bringForward(): void;

  /**
   * Moves the current shape to the back of its siblings
   */
  sendToBack(): void;

  /**
   * Moves the current shape one position backwards in its list of siblings
   */
  sendBackward(): void;

  /**
   * Generates an export from the current shape.
   *
   * @example
   * ```js
   * shape.export({ type: 'png', scale: 2 });
   * ```
   */
  export(config: Export): Promise<Uint8Array>;

  /**
   * The interactions for the current shape.
   */
  readonly interactions: Interaction[];

  /**
   * Adds a new interaction to the shape.
   * @param trigger defines the conditions under which the action will be triggered
   * @param action defines what will be executed when the trigger happens
   * @param delay for the type of trigger `after-delay` will specify the time after triggered. Ignored otherwise.
   *
   * @example
   * ```js
   * shape.addInteraction('click', { type: 'navigate-to', destination: anotherBoard });
   * ```
   */
  addInteraction(trigger: Trigger, action: Action, delay?: number): Interaction;

  /**
   * Removes the interaction from the shape.
   * @param interaction is the interaction to remove from the shape
   *
   * @example
   * ```js
   * shape.removeInteraction(interaction);
   * ```
   */
  removeInteraction(interaction: Interaction): void;

  /**
   * Applies one design token to one or more properties of the shape.
   * @param token is the Token to apply
   * @param properties an optional list of property names. If omitted, the
   * default properties will be applied.
   *
   * NOTE that the tokens application is by name and not by id. If there exist
   * several tokens with the same name in different sets, the actual token applied
   * and the value set to the attributes will depend on which sets are active
   * (and will change if different sets or themes are activated later).
   */
  applyToken(token: Token, properties: TokenProperty[] | undefined): void;

  /**
   * Creates a clone of the shape.
   * @return Returns a new instance of the shape with identical properties.
   */
  clone(): Shape;

  /**
   * Removes the shape from its parent.
   */
  remove(): void;
}

/**
 * Slide animation
 */
export interface Slide {
  /**
   * Type of the animation.
   */
  readonly type: 'slide';

  /**
   * Indicate if the slide will be either in-to-out `in` or out-to-in `out`.
   */
  readonly way: 'in' | 'out';

  /**
   * Direction for the slide animation.
   */
  readonly direction: 'right' | 'left' | 'up' | 'down';

  /**
   * Duration of the animation effect.
   */
  readonly duration: number;

  /**
   * If `true` the offset effect will be used.
   */
  readonly offsetEffect?: boolean;

  /**
   * Function that the dissolve effect will follow for the interpolation.
   * Defaults to `linear`.
   */
  readonly easing?: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
}

/**
 * Represents stroke properties in Penpot. You can add a stroke to any shape except for groups.
 * This interface includes properties for defining the color, style, width, alignment, and caps of a stroke.
 */
export interface Stroke {
  /**
   * The optional color of the stroke, represented as a string (e.g., '#FF5733').
   */
  strokeColor?: string;
  /**
   * The optional reference to an external file for the stroke color.
   */
  strokeColorRefFile?: string;
  /**
   * The optional reference ID within the external file for the stroke color.
   */
  strokeColorRefId?: string;
  /**
   * The optional opacity level of the stroke color, ranging from 0 (fully transparent) to 1 (fully opaque).
   * Defaults to 1 if omitted.
   */
  strokeOpacity?: number;
  /**
   * The optional style of the stroke.
   */
  strokeStyle?: 'solid' | 'dotted' | 'dashed' | 'mixed' | 'none' | 'svg';
  /**
   * The optional width of the stroke.
   */
  strokeWidth?: number;
  /**
   * The optional alignment of the stroke relative to the shape's boundary.
   */
  strokeAlignment?: 'center' | 'inner' | 'outer';
  /**
   * The optional cap style for the start of the stroke.
   */
  strokeCapStart?: StrokeCap;
  /**
   * The optional cap style for the end of the stroke.
   */
  strokeCapEnd?: StrokeCap;
  /**
   * The optional gradient stroke defined by a Gradient object.
   */
  strokeColorGradient?: Gradient;
}

/**
 * Represents the cap style of a stroke in Penpot.
 * This type defines various styles for the ends of a stroke.
 */
export type StrokeCap =
  | 'round'
  | 'square'
  | 'line-arrow'
  | 'triangle-arrow'
  | 'square-marker'
  | 'circle-marker'
  | 'diamond-marker';

/**
 * Represents an SVG raw shape in Penpot.
 * This interface extends `ShapeBase` and includes properties specific to raw SVG shapes.
 */
export interface SvgRaw extends ShapeBase {
  type: 'svg-raw';
}

/**
 * Text represents a text element in the Penpot application, extending the base shape interface.
 * It includes various properties to define the text content and its styling attributes.
 */
export interface Text extends ShapeBase {
  /**
   * The type of the shape, which is always 'text' for text shapes.
   */
  readonly type: 'text';
  /**
   * The characters contained within the text shape.
   */
  characters: string;
  /**
   * The grow type of the text shape, defining how the text box adjusts its size.
   * Possible values are:
   * - 'fixed': Fixed size.
   * - 'auto-width': Adjusts width automatically.
   * - 'auto-height': Adjusts height automatically.
   */
  growType: 'fixed' | 'auto-width' | 'auto-height';

  /**
   * The font ID used in the text shape, or 'mixed' if multiple fonts are used.
   */
  fontId: string | 'mixed';

  /**
   * The font family used in the text shape, or 'mixed' if multiple font families are used.
   */
  fontFamily: string | 'mixed';

  /**
   * The font variant ID used in the text shape, or 'mixed' if multiple font variants are used.
   */
  fontVariantId: string | 'mixed';

  /**
   * The font size used in the text shape, or 'mixed' if multiple font sizes are used.
   */
  fontSize: string | 'mixed';

  /**
   * The font weight used in the text shape, or 'mixed' if multiple font weights are used.
   */
  fontWeight: string | 'mixed';

  /**
   * The font style used in the text shape, or 'mixed' if multiple font styles are used.
   */
  fontStyle: 'normal' | 'italic' | 'mixed' | null;

  /**
   * The line height used in the text shape, or 'mixed' if multiple line heights are used.
   */
  lineHeight: string | 'mixed';

  /**
   * The letter spacing used in the text shape, or 'mixed' if multiple letter spacings are used.
   */
  letterSpacing: string | 'mixed';

  /**
   * The text transform applied to the text shape, or 'mixed' if multiple text transforms are used.
   */
  textTransform: 'uppercase' | 'capitalize' | 'lowercase' | 'mixed' | null;

  /**
   * The text decoration applied to the text shape, or 'mixed' if multiple text decorations are used.
   */
  textDecoration: 'underline' | 'line-through' | 'mixed' | null;

  /**
   * The text direction for the text shape, or 'mixed' if multiple directions are used.
   */
  direction: 'ltr' | 'rtl' | 'mixed' | null;

  /**
   * The horizontal alignment of the text shape. It can be a specific alignment or 'mixed' if multiple alignments are used.
   */
  align: 'left' | 'center' | 'right' | 'justify' | 'mixed' | null;

  /**
   * The vertical alignment of the text shape. It can be a specific alignment or 'mixed' if multiple alignments are used.
   */
  verticalAlign: 'top' | 'center' | 'bottom' | null;

  /**
   * Gets a text range within the text shape.
   * @param start - The start index of the text range.
   * @param end - The end index of the text range.
   * @return Returns a TextRange object representing the specified text range.
   *
   * @example
   * ```js
   * const textRange = textShape.getRange(0, 10);
   * console.log(textRange.characters);
   * ```
   */
  getRange(start: number, end: number): TextRange;

  /**
   * Applies a typography style to the text shape.
   * @param typography - The typography style to apply.
   * @remarks
   * This method sets various typography properties for the text shape according to the given typography style.
   *
   * @example
   * ```js
   * textShape.applyTypography(typography);
   * ```
   */
  applyTypography(typography: LibraryTypography): void;
}

/**
 * Represents a range of text within a Text shape.
 * This interface provides properties for styling and formatting text ranges.
 */
export interface TextRange {
  /**
   * The Text shape to which this text range belongs.
   */
  readonly shape: Text;

  /**
   * The characters associated with the current text range.
   */
  readonly characters: string;

  /**
   * The font ID of the text range. It can be a specific font ID or 'mixed' if multiple fonts are used.
   */
  fontId: string | 'mixed';

  /**
   * The font family of the text range. It can be a specific font family or 'mixed' if multiple font families are used.
   */
  fontFamily: string | 'mixed';

  /**
   * The font variant ID of the text range. It can be a specific font variant ID or 'mixed' if multiple font variants are used.
   */
  fontVariantId: string | 'mixed';

  /**
   * The font size of the text range. It can be a specific font size or 'mixed' if multiple font sizes are used.
   */
  fontSize: string | 'mixed';

  /**
   * The font weight of the text range. It can be a specific font weight or 'mixed' if multiple font weights are used.
   */
  fontWeight: string | 'mixed';

  /**
   * The font style of the text range. It can be a specific font style or 'mixed' if multiple font styles are used.
   */
  fontStyle: 'normal' | 'italic' | 'mixed' | null;

  /**
   * The line height of the text range. It can be a specific line height or 'mixed' if multiple line heights are used.
   */
  lineHeight: string | 'mixed';

  /**
   * The letter spacing of the text range. It can be a specific letter spacing or 'mixed' if multiple letter spacings are used.
   */
  letterSpacing: string | 'mixed';

  /**
   * The text transform applied to the text range. It can be a specific text transform or 'mixed' if multiple text transforms are used.
   */
  textTransform:
    | 'uppercase'
    | 'capitalize'
    | 'lowercase'
    | 'none'
    | 'mixed'
    | null;

  /**
   * The text decoration applied to the text range. It can be a specific text decoration or 'mixed' if multiple text decorations are used.
   */
  textDecoration: 'underline' | 'line-through' | 'none' | 'mixed' | null;

  /**
   * The text direction for the text range. It can be a specific direction or 'mixed' if multiple directions are used.
   */
  direction: 'ltr' | 'rtl' | 'mixed' | null;

  /**
   * The fill styles applied to the text range.
   */
  fills: Fill[] | 'mixed';

  /**
   * The horizontal alignment of the text range. It can be a specific alignment or 'mixed' if multiple alignments are used.
   */
  align: 'left' | 'center' | 'right' | 'justify' | 'mixed' | null;

  /**
   * The vertical alignment of the text range. It can be a specific alignment or 'mixed' if multiple alignments are used.
   */
  verticalAlign: 'top' | 'center' | 'bottom' | 'mixed' | null;

  /**
   * Applies a typography style to the text range.
   * This method sets various typography properties for the text range according to the given typography style.
   * @param typography - The typography style to apply.
   *
   * @example
   * ```js
   * textRange.applyTypography(typography);
   * ```
   */
  applyTypography(typography: LibraryTypography): void;
}

/**
 * This type specifies the possible themes: 'light' or 'dark'.
 */
export type Theme = 'light' | 'dark';

/**
 * It opens an overlay if it is not already opened or closes it if it is already opened.
 */
export interface ToggleOverlay extends OverlayAction {
  /**
   * The action type
   */
  readonly type: 'toggle-overlay';
}

/**
 * Represents a track configuration in Penpot.
 * This interface includes properties for defining the type and value of a track used in layout configurations.
 */
export interface Track {
  /**
   * The type of the track.
   * This can be one of the following values:
   * - 'flex': A flexible track type.
   * - 'fixed': A fixed track type.
   * - 'percent': A track type defined by a percentage.
   * - 'auto': An automatic track type.
   */
  type: TrackType;
  /**
   * The value of the track.
   * This can be a number representing the size of the track, or null if not applicable.
   */
  value: number | null;
}

/**
 * Represents the type of track in Penpot.
 * This type defines various track types that can be used in layout configurations.
 */
export type TrackType = 'flex' | 'fixed' | 'percent' | 'auto';

/**
 * Types of triggers defined:
 * - `click` triggers when the user uses the mouse to click on a shape
 * - `mouse-enter` triggers when the user moves the mouse inside the shape (even if no mouse button is pressed)
 * - `mouse-leave` triggers when the user moves the mouse outside the shape.
 * - `after-delay` triggers after the `delay` time has passed even if no interaction from the user happens.
 */
export type Trigger = 'click' | 'mouse-enter' | 'mouse-leave' | 'after-delay';

/**
 * Represents the base properties and methods of a Design Token in Penpot, shared by
 * all token types.
 */
export interface TokenBase {
  /**
   * The unique identifier for this token, used only internally inside Penpot.
   * This one is not exported or synced with external Design Token sources.
   */
  readonly id: string;

  /**
   * The name of the token. It may include a group path separated by `.`.
   */
  name: string;

  /**
   * An optional description text.
   */
  description: string;

  /**
   * Adds to the set that contains this Token a new one equal to this one
   * but with a new id.
   */
  duplicate(): Token;

  /**
   * Removes this token from the catalog.
   *
   * It will NOT be unapplied from any shape, since there may be other tokens
   * with the same name.
   */
  remove(): void;

  /**
   * Applies this token to one or more properties of the given shapes.
   * @param shapes is an array of shapes to apply it.
   * @param properties an optional list of property names. If omitted, the
   * default properties will be applied.
   *
   * NOTE that the tokens application is by name and not by id. If there exist
   * several tokens with the same name in different sets, the actual token applied
   * and the value set to the attributes will depend on which sets are active
   * (and will change if different sets or themes are activated later).
   */
  applyToShapes(shapes: Shape[], properties: TokenProperty[] | undefined): void;

  /**
   * Applies this token to the currently selected shapes.
   *
   * Parameters and warnings are the same as above.
   */
  applyToSelected(properties: TokenProperty[] | undefined): void;
}

/**
 * Represents a token of type BorderRadius.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenBorderRadius extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'borderRadius';

  /**
   * The value as defined in the token itself.
   * It's a positive number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a positive number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/*
 * The value of a TokenShadow in its composite form.
 */
export interface TokenShadowValue {
  /**
   * The color as a string (e.g. "#FF5733").
   */
  color: string;

  /**
   * If the shadow is inset or drop.
   */
  inset: boolean;

  /**
   * The horizontal offset of the shadow in pixels.
   */
  offsetX: number;

  /**
   * The vertical offset of the shadow in pixels.
   */
  offsetY: number;

  /**
   * The spread distance of the shadow in pixels.
   */
  spread: number;

  /**
   * The amount of blur to apply to the shadow.
   */
  blur: number;
}

/*
 * The value of a TokenShadow in its composite of strings form.
 */
export interface TokenShadowValueString {
  /**
   * The color as a string (e.g. "#FF5733"), or a reference
   * to a color token.
   */
  color: string;

  /**
   * If the shadow is inset or drop, or a reference of a
   * boolean token.
   */
  inset: string;

  /**
   * The horizontal offset of the shadow in pixels, or a reference
   * to a number token.
   */
  offsetX: string;

  /**
   * The vertical offset of the shadow in pixels, or a reference
   * to a number token.
   */
  offsetY: string;

  /**
   * The spread distance of the shadow in pixels, or a reference
   * to a number token.
   */
  spread: string;

  /**
   * The amount of blur to apply to the shadow, or a reference
   * to a number token.
   */
  blur: string;
}

/**
 * Represents a token of type Shadow.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenShadow extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'shadow';

  /**
   * The value as defined in the token itself.
   * It may be a string with a reference to other token, or else
   * an array of TokenShadowValueString.
   */
  value: string | TokenShadowValueString[];

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's an array of TokenShadowValue, or undefined if no value has been found
   * in active sets.
   */
  readonly resolvedValue: TokenShadowValue[] | undefined;
}

/**
 * Represents a token of type Color.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenColor extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'color';

  /**
   * The value as defined in the token itself.
   * It's a rgb color or a reference.
   */
  value: string;

  /**
   * The value as defined in the token itself.
   * It's a rgb color or a reference.
   */
  readonly resolvedValue: string | undefined;
}

/**
 * Represents a token of type Dimension.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenDimension extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'dimension';

  /**
   * The value as defined in the token itself.
   * It's a positive number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a positive number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type FontFamilies.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenFontFamilies extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'fontFamilies';

  /**
   * The value as defined in the token itself.
   * It may be a string with a reference to other token, or else
   * an array of strings with one or more font families (each family
   * is an item in the array).
   */
  value: string | string[];

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's an array of strings with one or more font families,
   * or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: string[] | undefined;
}

/**
 * Represents a token of type FontSizes.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenFontSizes extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'fontSizes';

  /**
   * The value as defined in the token itself.
   * It's a positive number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a positive number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type FontWeights.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenFontWeights extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'fontWeights';

  /**
   * The value as defined in the token itself.
   * It's a weight string or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a weight string ("bold", "strong", etc.), or undefined if no value has
   * been found in active sets.
   */
  readonly resolvedValue: string | undefined;
}

/**
 * Represents a token of type LetterSpacing.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenLetterSpacing extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'letterSpacing';

  /**
   * The value as defined in the token itself.
   * It's a number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type Number.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenNumber extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'number';

  /**
   * The value as defined in the token itself.
   * It's a number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type Opacity.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenOpacity extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'opacity';

  /**
   * The value as defined in the token itself.
   * It's a number between 0 and 1 or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a number between 0 and 1, or undefined if no value has been found
   * in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type Rotation.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenRotation extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'rotation';

  /**
   * The value as defined in the token itself.
   * It's a number in degrees or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a number in degrees, or undefined if no value has been found
   * in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type Sizing.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenSizing extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'sizing';

  /**
   * The value as defined in the token itself.
   * It's a number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type Spacing.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenSpacing extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'spacing';

  /**
   * The value as defined in the token itself.
   * It's a number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type BorderWidth.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenBorderWidth extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'borderWidth';

  /**
   * The value as defined in the token itself.
   * It's a positive number or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a positive number, or undefined if no value has been found in active sets.
   */
  readonly resolvedValue: number | undefined;
}

/**
 * Represents a token of type TextCase.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenTextCase extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'textCase';

  /**
   * The value as defined in the token itself.
   * It's a case string or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a case string ("none", "uppercase", "lowercase", "capitalize"), or
   * undefined if no value has been found in active sets.
   */
  readonly resolvedValue: string | undefined;
}

/**
 * Represents a token of type Decoration.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenTextDecoration extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'textDecoration';

  /**
   * The value as defined in the token itself.
   * It's a decoration string or a reference.
   */
  value: string;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a decoration string, or undefined if no value has been found
   * in active sets.
   */
  readonly resolvedValue: string | undefined;
}

/*
 * The value of a TokenTypography in its composite form.
 */
export interface TokenTypographyValue {
  /**
   * The letter spacing, as a number.
   */
  letterSpacing: number;

  /**
   * The list of font families.
   */
  fontFamilies: string[];

  /**
   * The font size, as a positive number.
   */
  fontSizes: number;

  /**
   * The font weight, as a weight string ("bold", "strong", etc.).
   */
  fontWeights: string;

  /**
   * The line height, as a number.
   */
  lineHeight: number;

  /**
   * The text case as a string ("none", "uppercase", "lowercase" "capitalize").
   */
  textCase: string;

  /**
   * The text decoration as a string ("none", "underline", "strike-through").
   */
  textDecoration: string;
}

/*
 * The value of a TokenTypography in its composite of strings form.
 */
export interface TokenTypographyValueString {
  /**
   * The letter spacing, as a number, or a reference to a TokenLetterSpacing.
   */
  letterSpacing: string;

  /**
   * The list of font families, or a reference to a TokenFontFamilies.
   */
  fontFamilies: string | string[];

  /**
   * The font size, as a positive number, or a reference to a TokenFontSizes.
   */
  fontSizes: string;

  /**
   * The font weight, as a weight string ("bold", "strong", etc.), or a
   * reference to a TokenFontWeights.
   */
  fontWeight: string;

  /**
   * The line height, as a number. Note that there not exists an individual
   * token type line height, only part of a Typography token. If you need to
   * put here a reference, use a NumberToken.
   */
  lineHeight: string;

  /**
   * The text case as a string ("none", "uppercase", "lowercase" "capitalize"),
   * or a reference to a TokenTextCase.
   */
  textCase: string;

  /**
   * The text decoration as a string ("none", "underline", "strike-through"),
   * or a reference to a TokenTextDecoration.
   */
  textDecoration: string;
}

/**
 * Represents a token of type Typography.
 * This interface extends `TokenBase` and specifies the data type of the value.
 */
export interface TokenTypography extends TokenBase {
  /**
   * The type of the token.
   */
  readonly type: 'typography';

  /**
   * The value as defined in the token itself.
   * It may be a string with a reference to other token, or a
   * TokenTypographyValueString.
   */
  value: string | TokenTypographyValueString;

  /**
   * The value calculated by finding all tokens with the same name in active sets
   * and resolving the references.
   *
   * It's a TokenTypographyValue, or undefined if no value has been found
   * in active sets.
   */
  readonly resolvedValue: TokenTypographyValue[] | undefined;
}

/**
 * Any possible type of value field in a token.
 */
export type TokenValueString =
  | TokenShadowValueString
  | TokenTypographyValueString
  | string
  | string[];

/**
 * The supported Design Tokens in Penpot.
 */
export type Token =
  | TokenBorderRadius
  | TokenShadow
  | TokenColor
  | TokenDimension
  | TokenFontFamilies
  | TokenFontSizes
  | TokenFontWeights
  | TokenLetterSpacing
  | TokenNumber
  | TokenOpacity
  | TokenRotation
  | TokenSizing
  | TokenSpacing
  | TokenBorderWidth
  | TokenTextCase
  | TokenTextDecoration
  | TokenTypography;

/**
 * The collection of all tokens in a Penpot file's library.
 *
 * Tokens are contained in sets, that can be marked as active
 * or inactive to control the resolved value of the tokens.
 *
 * The active status of sets can be handled by presets named
 * Themes.
 */
export interface TokenCatalog {
  /**
   * The list of themes in this catalog, in creation order.
   */
  readonly themes: TokenTheme[];

  /**
   * The  list of sets in this catalog, in the order defined
   * by the user. The order is important because then same token name
   * exists in several active sets, the latter has precedence.
   */
  readonly sets: TokenSet[];

  /**
   * Creates a new TokenTheme and adds it to the catalog.
   * @param group The group name of the theme (can be empty string).
   * @param name The name of the theme (required)
   * @return Returns the created TokenTheme.
   */
  addTheme(group: string, name: string): TokenTheme;

  /**
   * Creates a new TokenSet and adds it to the catalog.
   * @param name The name of the set (required). It may contain
   * a group path, separated by `/`.
   * @return Returns the created TokenSet.
   */
  addSet(name: string): TokenSet;

  /**
   * Retrieves a theme.
   * @param id the id of the theme.
   * @returns Returns the theme or undefined if not found.
   */
  getThemeById(id: string): TokenTheme | undefined;

  /**
   * Retrieves a set.
   * @param id the id of the set.
   * @returns Returns the set or undefined if not found.
   */
  getSetById(id: string): TokenSet | undefined;
}

/**
 * A collection of Design Tokens.
 *
 * Inside a set, tokens have an unique name, that will designate
 * what token to use if the name is applied to a shape and this
 * set is active.
 */
export interface TokenSet {
  /**
   * The unique identifier for this set, used only internally inside Penpot.
   * This one is not exported or synced with external Design Token sources.
   */
  readonly id: string;

  /**
   * The name of the set. It may include a group path separated by `/`.
   */
  name: string;

  /**
   * Indicates if the set is currently active.
   */
  active: boolean;

  /**
   * The tokens contained in this set, in alphabetical order.
   */
  readonly tokens: Token[];

  /**
   * The tokens contained in this set, grouped by type.
   */
  readonly tokensByType: [string, Token[]][];

  /**
   * Toggles the active status of this set.
   */
  toggleActive(): void;

  /**
   * Retrieves a token.
   * @param id the id of the token.
   * @returns Returns the token or undefined if not found.
   */
  getTokenById(id: string): Token | undefined;

  /**
   * Creates a new Token and adds it to the set.
   * @param type Thetype of token.
   * @param name The name of the token (required). It may contain
   * a group path, separated by `.`.
   * @param value The value of the token (required), in the string form.
   * @return Returns the created Token.
   */
  addToken(type: TokenType, name: string, value: TokenValueString): Token;

  /**
   * Adds to the catalog a new TokenSet equal to this one but with a new id.
   */
  duplicate(): TokenSet;

  /**
   * Removes this set from the catalog.
   */
  remove(): void;
}

/**
 * A preset of active TokenSets.
 *
 * A theme contains a list of references to TokenSets. When the theme
 * is activated, it sets are activated too. This will not deactivate
 * sets that are _not_ in this theme, because they may have been
 * activated by other themes.
 *
 * Themes may be gruped. At any time only one of the themes in a group
 * may be active. But there may be active themes in other groups. This
 * allows to define multiple "axis" for theming (e.g. color scheme,
 * density or brand).
 *
 * When a TokenSet is activated or deactivated directly, all themes
 * are disabled (indicating that now there is a "custom" manual theme
 * active).
 */
export interface TokenTheme {
  /**
   * The unique identifier for this theme, used only internally inside Penpot.
   * This one is not exported or synced with external Design Token sources.
   */
  readonly id: string;

  /**
   * Optional identifier that may exists if the theme was imported from an
   * external tool that uses ids in the json file.
   */
  readonly externalId: string | undefined;

  /**
   * The group name of the theme. Can be empt string.
   */
  group: string;

  /**
   * The name of the theme.
   */
  name: string;

  /**
   * Indicates if the theme is currently active.
   */
  active: boolean;

  /**
   * Toggles the active status of this theme.
   */
  toggleActive(): void;

  /**
   * The sets that will be activated if this theme is activated.
   */
  activeSets: TokenSet[];

  /**
   * Adds a set to the list of the theme.
   */
  addSet(tokenSet: TokenSet): void;

  /**
   * Removes a set from the list of the theme.
   */
  removeSet(tokenSet: TokenSet): void;

  /**
   * Adds to the catalog a new TokenTheme equal to this one but with a new id.
   */
  duplicate(): TokenTheme;

  /**
   * Removes this theme from the catalog.
   */
  remove(): void;
}

/**
 * The properties that a BorderRadius token can be applied to.
 */
type TokenBorderRadiusProps = 'r1' | 'r2' | 'r3' | 'r4';

/**
 * The properties that a Shadow token can be applied to.
 */
type TokenShadowProps = 'shadow';

/**
 * The properties that a Color token can be applied to.
 */
type TokenColorProps = 'fill' | 'stroke';

/**
 * The properties that a Dimension token can be applied to.
 */
type TokenDimensionProps =
  // Axis
  | 'x'
  | 'y'

  // Stroke width
  | 'stroke-width';

/**
 * The properties that a FontFamilies token can be applied to.
 */
type TokenFontFamiliesProps = 'font-families';

/**
 * The properties that a FontSizes token can be applied to.
 */
type TokenFontSizesProps = 'font-size';

/**
 * The properties that a FontWeight token can be applied to.
 */
type TokenFontWeightProps = 'font-weight';

/**
 * The properties that a LetterSpacing token can be applied to.
 */
type TokenLetterSpacingProps = 'letter-spacing';

/**
 * The properties that a Number token can be applied to.
 */
type TokenNumberProps = 'rotation' | 'line-height';

/**
 * The properties that an Opacity token can be applied to.
 */
type TokenOpacityProps = 'opacity';

/**
 * The properties that a Sizing token can be applied to.
 */
type TokenSizingProps =
  // Size
  | 'width'
  | 'height'

  // Layout
  | 'layout-item-min-w'
  | 'layout-item-max-w'
  | 'layout-item-min-h'
  | 'layout-item-max-h';

/**
 * The properties that a Spacing token can be applied to.
 */
type TokenSpacingProps =
  // Spacing / Gap
  | 'row-gap'
  | 'column-gap'

  // Spacing / Padding
  | 'p1'
  | 'p2'
  | 'p3'
  | 'p4'

  // Spacing / Margin
  | 'm1'
  | 'm2'
  | 'm3'
  | 'm4';

/**
 * The properties that a BorderWidth token can be applied to.
 */
type TokenBorderWidthProps = 'stroke-width';

/**
 * The properties that a TextCase token can be applied to.
 */
type TokenTextCaseProps = 'text-case';

/**
 * The properties that a TextDecoration token can be applied to.
 */
type TokenTextDecorationProps = 'text-decoration';

/**
 * The properties that a Typography token can be applied to.
 */
type TokenTypographyProps = 'typography';

/**
 * All the properties that a token can be applied to.
 * Not always correspond to Shape properties. For example,
 * `fill` property applies to `fillColor` of the first fill
 * of the shape.
 *
 */
export type TokenProperty =
  | 'all'
  | TokenBorderRadiusProps
  | TokenShadowProps
  | TokenColorProps
  | TokenDimensionProps
  | TokenFontFamiliesProps
  | TokenFontSizesProps
  | TokenFontWeightProps
  | TokenLetterSpacingProps
  | TokenNumberProps
  | TokenOpacityProps
  | TokenSizingProps
  | TokenSpacingProps
  | TokenBorderWidthProps
  | TokenTextCaseProps
  | TokenTextDecorationProps
  | TokenTypographyProps;

/**
 * The supported types of Design Tokens in Penpot.
 */
export type TokenType =
  | 'borderRadius'
  | 'shadow'
  | 'color'
  | 'dimension'
  | 'fontFamilies'
  | 'fontSizes'
  | 'fontWeights'
  | 'letterSpacing'
  | 'number'
  | 'opacity'
  | 'rotation'
  | 'sizing'
  | 'spacing'
  | 'borderWidth'
  | 'textCase'
  | 'textDecoration'
  | 'typography';

/**
 * Represents a user in Penpot.
 */
export interface User {
  /**
   * The unique identifier of the user.
   *
   * @example
   * ```js
   * const userId = user.id;
   * console.log(userId);
   * ```
   */
  readonly id: string;

  /**
   * The name of the user.
   *
   * @example
   * ```js
   * const userName = user.name;
   * console.log(userName);
   * ```
   */
  readonly name?: string;

  /**
   * The URL of the user's avatar image.
   *
   * @example
   * ```js
   * const avatarUrl = user.avatarUrl;
   * console.log(avatarUrl);
   * ```
   */
  readonly avatarUrl?: string;

  /**
   * The color associated with the user.
   *
   * @example
   * ```js
   * const userColor = user.color;
   * console.log(userColor);
   * ```
   */
  readonly color: string;

  /**
   * The session ID of the user.
   *
   * @example
   * ```js
   * const sessionId = user.sessionId;
   * console.log(sessionId);
   * ```
   */
  readonly sessionId?: string;
}

/**
 * TODO
 */
export interface Variants {
  /**
   * The unique identifier of the variant element. It is the id of the VariantContainer, and all the VariantComponents
   * that belong to this variant have an attribute variantId which this is as value.
   */
  readonly id: string;

  /**
   * The unique identifier of the library to which the variant belongs.
   */
  readonly libraryId: string;

  /**
   * A list with the names of the properties of the Variant
   */
  properties: string[];

  /**
   * A list of all the values of a property along all the variantComponents of this Variant
   * @param property The name of the property
   */
  currentValues(property: string): string[];

  /**
   * Remove a property of the Variant
   * @param pos The position of the property to remove
   */
  removeProperty(pos: number): void;

  /**
   * Rename a property of the Variant
   * @param pos The position of the property to rename
   * @param name The new name of the property
   */
  renameProperty(pos: number, name: string): void;

  /**
   * List all the VariantComponents on this Variant.
   */
  variantComponents(): LibraryComponent[];

  /**
   * Creates a duplicate of the main VariantComponent of this Variant
   */
  addVariant(): void;

  /**
   * Adds a new property to this Variant
   */
  addProperty(): void;
}

/**
 * Viewport represents the viewport in the Penpot application.
 * It includes the center point, zoom level, and the bounds of the viewport.
 */
export interface Viewport {
  /**
   * the `center` point of the current viewport. If changed will change the
   * viewport position.
   */
  center: Point;

  /**
   * the `zoom` level as a number where `1` represents 100%.
   */
  zoom: number;

  /**
   * the `bounds` are the current coordinates of the viewport.
   */
  readonly bounds: Bounds;

  /**
   * Resets the zoom level.
   */
  zoomReset(): void;

  /**
   * Changes the viewport and zoom so can fit all the current shapes in the page.
   */
  zoomToFitAll(): void;

  /**
   * Changes the viewport and zoom so all the `shapes` in the argument are
   * visible.
   */
  zoomIntoView(shapes: Shape[]): void;
}

declare global {
  const penpot: Penpot;
}
