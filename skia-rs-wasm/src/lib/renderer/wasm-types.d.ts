/**
 * TypeScript definitions for the render-wasm module
 * Based on Emscripten-generated WASM module interface
 */

export interface WasmModule {
  // Memory heaps
  readonly HEAP8: Int8Array
  readonly HEAPU8: Uint8Array
  readonly HEAP16: Int16Array
  readonly HEAPU16: Uint16Array
  readonly HEAP32: Int32Array
  readonly HEAPU32: Uint32Array
  readonly HEAPF32: Float32Array
  readonly HEAPF64: Float64Array

  // WebGL context
  readonly GL: {
    deleteContext(handle: number): void
    registerContext(
      context: WebGL2RenderingContext,
      attributes?: { majorVersion: number }
    ): number
    makeContextCurrent(handle: number): void
    getContext(handle: number): WebGL2RenderingContext | null
    textures: { [key: number]: WebGLTexture }
    getNewId(objects: { [key: number]: unknown }): number
  }

  // Memory management
  _malloc(size: number): number
  _free(ptr: number): void
  stringToUTF8(str: string, outPtr: number, maxBytesToWrite: number): void

  // Initialization
  _init(width: number, height: number): void
  _init_shapes_pool(count: number): void
  _set_browser(browser: number): void
  _set_render_options(debug: number, dpr: number): void
  _clean_up(): void

  // Canvas/Viewport
  _resize_viewbox(width: number, height: number): void
  _set_view(zoom: number, x: number, y: number): void
  _set_view_start(): void
  _set_view_end(): void
  _set_canvas_background(color: number): void
  _reset_canvas(): void

  // Rendering
  _render(timestamp: number): void
  _render_sync(): void
  _render_sync_shape(a: number, b: number, c: number, d: number): void
  _render_from_cache(timestamp: number): void
  _process_animation_frame(timestamp: number): void

  // Shapes
  _use_shape(a: number, b: number, c: number, d: number): void
  _set_parent(a: number, b: number, c: number, d: number): void
  _set_shape_type(type: number): void
  _set_shape_clip_content(clip_content: number): void
  _set_shape_masked_group(masked: number): void
  _set_shape_selrect(left: number, top: number, right: number, bottom: number): void
  _set_shape_transform(a: number, b: number, c: number, d: number, e: number, f: number): void
  _set_shape_rotation(rotation: number): void
  _set_shape_opacity(opacity: number): void
  _set_shape_hidden(hidden: number): void
  _set_shape_blend_mode(mode: number): void
  _set_shape_vertical_align(align: number): void

  // Children
  _set_children_0(): void
  _set_children_1(a1: number, b1: number, c1: number, d1: number): void
  _set_children_2(
    a1: number,
    b1: number,
    c1: number,
    d1: number,
    a2: number,
    b2: number,
    c2: number,
    d2: number
  ): void
  _set_children_3(
    a1: number,
    b1: number,
    c1: number,
    d1: number,
    a2: number,
    b2: number,
    c2: number,
    d2: number,
    a3: number,
    b3: number,
    c3: number,
    d3: number
  ): void
  _set_children_4(
    a1: number,
    b1: number,
    c1: number,
    d1: number,
    a2: number,
    b2: number,
    c2: number,
    d2: number,
    a3: number,
    b3: number,
    c3: number,
    d3: number,
    a4: number,
    b4: number,
    c4: number,
    d4: number
  ): void
  _set_children_5(
    a1: number,
    b1: number,
    c1: number,
    d1: number,
    a2: number,
    b2: number,
    c2: number,
    d2: number,
    a3: number,
    b3: number,
    c3: number,
    d3: number,
    a4: number,
    b4: number,
    c4: number,
    d4: number,
    a5: number,
    b5: number,
    c5: number,
    d5: number
  ): void
  _set_children(): void
  _add_shape_child(a: number, b: number, c: number, d: number): void

  // Fills
  _set_shape_fills(): void
  _add_shape_fill(): void
  _clear_shape_fills(): void

  // Strokes
  _add_shape_center_stroke(width: number, style: number, cap_start: number, cap_end: number): void
  _add_shape_inner_stroke(width: number, style: number, cap_start: number, cap_end: number): void
  _add_shape_outer_stroke(width: number, style: number, cap_start: number, cap_end: number): void
  _add_shape_stroke_fill(): void
  _clear_shape_strokes(): void

  // Shadows
  _add_shape_shadow(
    rgba: number,
    blur: number,
    spread: number,
    x: number,
    y: number,
    style: number,
    hidden: number
  ): void
  _clear_shape_shadows(): void

  // Blur
  _set_shape_blur(blur_type: number, hidden: number, value: number): void
  _clear_shape_blur(): void

  // Corners
  _set_shape_corners(r1: number, r2: number, r3: number, r4: number): void

  // Constraints
  _set_shape_constraint_h(constraint: number): void
  _set_shape_constraint_v(constraint: number): void
  _clear_shape_constraints(): void

  // Path
  _start_shape_path_buffer(): void
  _set_shape_path_chunk_buffer(): void
  _set_shape_path_buffer(): void
  _set_shape_path_content(): void
  _set_shape_bool_type(bool_type: number): void
  _calculate_bool(bool_type: number): number

  // Temporary objects (for boolean calculations)
  _start_temp_objects(): void
  _end_temp_objects(): void

  // Text
  _clear_shape_text(): void
  _set_shape_text_content(): void
  _set_shape_grow_type(grow_type: number): void
  _update_shape_text_layout(): void
  _get_text_dimensions(): number
  _calculate_position_data(): number

  // SVG
  _set_shape_svg_raw_content(): void
  _set_shape_svg_attrs(
    fill_rule: number,
    stroke_linecap: number,
    stroke_linejoin: number,
    fill_none: number
  ): void

  // Images
  _store_image(): void
  _store_image_from_texture(): void
  _is_image_cached(a: number, b: number, c: number, d: number, is_thumbnail: boolean): number

  // Fonts
  _store_font(
    shape_a: number,
    shape_b: number,
    shape_c: number,
    shape_d: number,
    font_a: number,
    font_b: number,
    font_c: number,
    font_d: number,
    weight: number,
    style: number,
    emoji: number,
    fallback: number
  ): void
  _is_font_uploaded(
    a: number,
    b: number,
    c: number,
    d: number,
    weight: number,
    style: number,
    emoji: number
  ): number

  // Layout
  _clear_shape_layout(): void
  _set_layout_data(
    margin_top: number,
    margin_right: number,
    margin_bottom: number,
    margin_left: number,
    h_sizing: number,
    v_sizing: number,
    has_max_h: number,
    max_h: number,
    has_min_h: number,
    min_h: number,
    has_max_w: number,
    max_w: number,
    has_min_w: number,
    min_w: number,
    align_self: number,
    is_absolute: number,
    z_index: number
  ): void

  // Focus mode
  _clear_focus_mode(): void
  _set_focus_mode(): void

  // Grid
  _show_grid(a: number, b: number, c: number, d: number): void
  _hide_grid(): void
  _get_grid_coords(x: number, y: number): number

  // Layout
  _set_flex_layout_data(
    dir: number,
    row_gap: number,
    column_gap: number,
    align_items: number,
    align_content: number,
    justify_items: number,
    justify_content: number,
    wrap_type: number,
    padding_top: number,
    padding_right: number,
    padding_bottom: number,
    padding_left: number
  ): void
  _set_grid_layout_data(
    dir: number,
    row_gap: number,
    column_gap: number,
    align_items: number,
    align_content: number,
    justify_items: number,
    justify_content: number,
    padding_top: number,
    padding_right: number,
    padding_bottom: number,
    padding_left: number
  ): void
  _set_grid_rows(): void
  _set_grid_columns(): void
  _set_grid_cells(): void

  // Selection and intersection
  _get_selection_rect(): number
  _intersect_position_in_shape(a: number, b: number, c: number, d: number, x: number, y: number): number

  // Path conversion
  _current_to_path(): number

  // Modifiers
  _set_modifiers(): void
  _propagate_modifiers(pixelPrecision: number): number
  _clean_modifiers(): void
  _set_structure_modifiers(): void

  // Memory allocation
  _alloc_bytes(len: number): number
  _free_bytes(): void
}

export type WasmModuleFactory = (options?: {
  locateFile?: (path: string, prefix: string) => string
  [key: string]: unknown
}) => Promise<WasmModule>

