;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport-wasm
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.flex-controls :as mfc]
   [app.main.ui.hooks :as ui-hooks]
   [app.main.ui.measurements :as msr]
   [app.main.ui.workspace.shapes.text.editor :as editor-v1]
   [app.main.ui.workspace.shapes.text.text-edition-outline :refer [text-edition-outline]]
   [app.main.ui.workspace.shapes.text.v2-editor :as editor-v2]
   [app.main.ui.workspace.shapes.text.viewport-texts-html :as stvh]
   [app.main.ui.workspace.viewport.actions :as actions]
   [app.main.ui.workspace.viewport.comments :as comments]
   [app.main.ui.workspace.viewport.debug :as wvd]
   [app.main.ui.workspace.viewport.drawarea :as drawarea]
   [app.main.ui.workspace.viewport.frame-grid :as frame-grid]
   [app.main.ui.workspace.viewport.gradients :as gradients]
   [app.main.ui.workspace.viewport.grid-layout-editor :as grid-layout]
   [app.main.ui.workspace.viewport.guides :as guides]
   [app.main.ui.workspace.viewport.hooks :as hooks]
   [app.main.ui.workspace.viewport.interactions :as interactions]
   [app.main.ui.workspace.viewport.outline :as outline]
   [app.main.ui.workspace.viewport.pixel-overlay :as pixel-overlay]
   [app.main.ui.workspace.viewport.presence :as presence]
   [app.main.ui.workspace.viewport.rulers :as rulers]
   [app.main.ui.workspace.viewport.scroll-bars :as scroll-bars]
   [app.main.ui.workspace.viewport.selection :as selection]
   [app.main.ui.workspace.viewport.snap-distances :as snap-distances]
   [app.main.ui.workspace.viewport.snap-points :as snap-points]
   [app.main.ui.workspace.viewport.top-bar :as top-bar]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.main.ui.workspace.viewport.viewport-ref :refer [create-viewport-ref]]
   [app.main.ui.workspace.viewport.widgets :as widgets]
   [app.render-wasm.api :as wasm.api]
   [app.util.debug :as dbg]
   [beicon.v2.core :as rx]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; --- Viewport

(defn apply-modifiers-to-selected
  [selected objects text-modifiers modifiers]
  (reduce
   (fn [objects id]
     (update
      objects id
      (fn [shape]
        (cond-> shape
          (and (cfh/text-shape? shape) (contains? text-modifiers id))
          (dwm/apply-text-modifier (get text-modifiers id))

          (contains? modifiers id)
          (gsh/transform-shape (dm/get-in modifiers [id :modifiers]))))))

   objects
   selected))

(mf/defc viewport*
  [{:keys [selected wglobal wlocal layout file page palete-size]}]
  (let [;; When adding data from workspace-local revisit `app.main.ui.workspace` to check
        ;; that the new parameter is sent
        {:keys [edit-path
                panning
                selrect
                transform
                highlighted
                vbox
                vport
                zoom
                zoom-inverse
                edition]} wlocal

        {:keys [options-mode
                tooltip
                show-distances?
                picking-color?]} wglobal

        permissions       (mf/use-ctx ctx/permissions)
        read-only?        (mf/use-ctx ctx/workspace-read-only?)

        ;; DEREFS

        drawing           (mf/deref refs/workspace-drawing)
        focus             (mf/deref refs/workspace-focus-selected)

        objects           (get page :objects)
        page-id           (get page :id)
        background        (get page :background clr/canvas)

        base-objects      (ui-hooks/with-focus-objects objects focus)

        modifiers         (mf/deref refs/workspace-modifiers)
        text-modifiers    (mf/deref refs/workspace-text-modifier)

        objects-modified  (mf/with-memo [base-objects text-modifiers modifiers]
                            (binding [cts/*wasm-sync* true]
                              (-> (into selected (keys modifiers))
                                  (apply-modifiers-to-selected base-objects text-modifiers modifiers))))

        selected-shapes   (keep (d/getf objects-modified) selected)

        ;; STATE
        alt?              (mf/use-state false)
        shift?            (mf/use-state false)
        mod?              (mf/use-state false)
        space?            (mf/use-state false)
        z?                (mf/use-state false)
        cursor            (mf/use-state (utils/get-cursor :pointer-inner))
        hover-ids         (mf/use-state nil)
        hover             (mf/use-state nil)
        measure-hover     (mf/use-state nil)
        hover-disabled?   (mf/use-state false)
        hover-top-frame-id (mf/use-state nil)
        frame-hover       (mf/use-state nil)
        active-frames     (mf/use-state #{})
        canvas-init?      (mf/use-state false)
        initialized?      (mf/use-state false)

        ;; REFS
        [viewport-ref
         on-viewport-ref] (create-viewport-ref)

        canvas-ref        (mf/use-ref nil)

        ;; VARS
        disable-paste     (mf/use-var false)
        in-viewport?      (mf/use-var false)

        ;; STREAMS
        move-stream       (mf/use-memo #(rx/subject))

        guide-frame       (mf/use-memo
                           (mf/deps @hover-ids base-objects)
                           (fn []
                             (let [parent-id
                                   (->> @hover-ids
                                        (d/seek (partial cfh/root-frame? base-objects)))]
                               (when (some? parent-id)
                                 (get base-objects parent-id)))))

        zoom              (d/check-num zoom 1)
        drawing-tool      (:tool drawing)
        drawing-obj       (:object drawing)


        selected-frames   (into #{} (map :frame-id) selected-shapes)

        ;; Only when we have all the selected shapes in one frame
        selected-frame    (when (= (count selected-frames) 1) (get base-objects (first selected-frames)))

        editing-shape     (when edition (get base-objects edition))

        create-comment?   (= :comments drawing-tool)
        drawing-path?     (or (and edition (= :draw (get-in edit-path [edition :edit-mode])))
                              (and (some? drawing-obj) (= :path (:type drawing-obj))))
        node-editing?     (and edition (= :path (get-in base-objects [edition :type])))
        text-editing?     (and edition (= :text (get-in base-objects [edition :type])))
        grid-editing?     (and edition (ctl/grid-layout? base-objects edition))

        mode-inspect?       (= options-mode :inspect)

        on-click          (actions/on-click hover selected edition drawing-path? drawing-tool space? selrect z?)
        on-context-menu   (actions/on-context-menu hover hover-ids read-only?)
        on-double-click   (actions/on-double-click hover hover-ids hover-top-frame-id drawing-path? base-objects edition drawing-tool z? read-only?)

        comp-inst-ref     (mf/use-ref false)
        on-drag-enter     (actions/on-drag-enter comp-inst-ref)
        on-drag-over      (actions/on-drag-over move-stream)
        on-drag-end       (actions/on-drag-over comp-inst-ref)
        on-drop           (actions/on-drop file comp-inst-ref)
        on-pointer-down   (actions/on-pointer-down @hover selected edition drawing-tool text-editing? node-editing? grid-editing?
                                                   drawing-path? create-comment? space? panning z? read-only?)

        on-pointer-up     (actions/on-pointer-up disable-paste)

        on-pointer-enter  (actions/on-pointer-enter in-viewport?)
        on-pointer-leave  (actions/on-pointer-leave in-viewport?)
        on-pointer-move   (actions/on-pointer-move move-stream)
        on-move-selected  (actions/on-move-selected hover hover-ids selected space? z? read-only?)
        on-menu-selected  (actions/on-menu-selected hover hover-ids selected read-only?)

        on-frame-enter    (actions/on-frame-enter frame-hover)
        on-frame-leave    (actions/on-frame-leave frame-hover)
        on-frame-select   (actions/on-frame-select selected read-only?)

        disable-events?          (contains? layout :comments)
        show-comments?           (= drawing-tool :comments)
        show-cursor-tooltip?     tooltip
        show-draw-area?          drawing-obj
        show-gradient-handlers?  (= (count selected) 1)
        show-grids?              (contains? layout :display-guides)

        show-frame-outline?      (= transform :move)
        show-outlines?           (and (nil? transform)
                                      (not edition)
                                      (not drawing-obj)
                                      (not (#{:comments :path :curve} drawing-tool)))

        show-pixel-grid?         (and (contains? layout :show-pixel-grid)
                                      (>= zoom 8))
        show-text-editor?        (and editing-shape (= :text (:type editing-shape)))

        hover-grid?              (and (some? @hover-top-frame-id)
                                      (ctl/grid-layout? objects @hover-top-frame-id))

        show-grid-editor?        (and editing-shape (ctl/grid-layout? editing-shape))
        show-presence?           page-id
        show-prototypes?         (= options-mode :prototype)
        show-selection-handlers? (and (seq selected) (not show-text-editor?))
        show-snap-distance?      (and (contains? layout :dynamic-alignment)
                                      (= transform :move)
                                      (seq selected))
        show-snap-points?        (and (or (contains? layout :dynamic-alignment)
                                          (contains? layout :snap-guides))
                                      (or drawing-obj transform))
        show-selrect?            (and selrect (empty? drawing) (not text-editing?))
        show-measures?           (and (not transform)
                                      (not node-editing?)
                                      (or show-distances? mode-inspect?))
        show-artboard-names?     (contains? layout :display-artboard-names)
        hide-ui?                 (contains? layout :hide-ui)
        show-rulers?             (and (contains? layout :rulers) (not hide-ui?))


        disabled-guides?         (or drawing-tool transform drawing-path? node-editing?)

        single-select?           (= (count selected-shapes) 1)

        first-shape (first selected-shapes)

        show-padding?
        (and (nil? transform)
             single-select?
             (= (:type first-shape) :frame)
             (= (:layout first-shape) :flex)
             (zero? (:rotation first-shape)))

        show-margin?
        (and (nil? transform)
             single-select?
             (= (:layout selected-frame) :flex)
             (zero? (:rotation first-shape)))

        selecting-first-level-frame?
        (and single-select? (cfh/root-frame? first-shape))

        offset-x (if selecting-first-level-frame?
                   (:x first-shape)
                   (:x selected-frame))


        offset-y (if selecting-first-level-frame?
                   (:y first-shape)
                   (:y selected-frame))
        rule-area-size (/ rulers/ruler-area-size zoom)
        preview-blend (-> refs/workspace-preview-blend
                          (mf/deref))]

    ;; NOTE: We need this page-id dependency to react to it and reset the
    ;;       canvas, even though we are not using `page-id` inside the hook.
    ;;       We think moving this out to a handler will make the render code
    ;;       harder to follow through.
    (mf/with-effect [page-id]
      (when-let [canvas (mf/ref-val canvas-ref)]
        (->> wasm.api/module
             (p/fmap (fn [ready?]
                       (when ready?
                         (reset! canvas-init? true)
                         (wasm.api/assign-canvas canvas)))))
        (fn []
          (wasm.api/clear-canvas))))

    (mf/with-effect [vport]
      (when @canvas-init?
        (wasm.api/resize-viewbox (:width vport) (:height vport))))

    (mf/with-effect [@canvas-init?  base-objects]
      (when (and @canvas-init? @initialized?)
        (wasm.api/set-objects base-objects)))

    (mf/with-effect [@canvas-init? preview-blend]
      (when (and @canvas-init? preview-blend)
        (wasm.api/request-render "with-effect")))

    (mf/with-effect [@canvas-init? zoom vbox background]
      (when (and @canvas-init? (not @initialized?))
        (wasm.api/initialize base-objects zoom vbox background)
        (reset! initialized? true)))

    (mf/with-effect [vbox]
      (when (and @canvas-init? initialized?)
        (wasm.api/set-view-zoom zoom vbox)))

    (mf/with-effect [vbox]
      (when (and @canvas-init? initialized?)
        (wasm.api/set-view-box zoom vbox)))

    (mf/with-effect [background]
      (when (and @canvas-init? initialized?)
        (wasm.api/set-canvas-background background)))

    (hooks/setup-dom-events zoom disable-paste in-viewport? read-only? drawing-tool drawing-path?)
    (hooks/setup-viewport-size vport viewport-ref)
    (hooks/setup-cursor cursor alt? mod? space? panning drawing-tool drawing-path? node-editing? z? read-only?)
    (hooks/setup-keyboard alt? mod? space? z? shift?)
    (hooks/setup-hover-shapes page-id move-stream base-objects transform selected mod? hover measure-hover
                              hover-ids hover-top-frame-id @hover-disabled? focus zoom show-measures?)
    (hooks/setup-shortcuts node-editing? drawing-path? text-editing? grid-editing?)
    (hooks/setup-active-frames base-objects hover-ids selected active-frames zoom transform vbox)

    [:div {:class (stl/css :viewport) :style #js {"--zoom" zoom} :data-testid "viewport"}
     (when (:can-edit permissions)
       [:& top-bar/top-bar {:layout layout}])
     [:div {:class (stl/css :viewport-overlays)}
      ;; The behaviour inside a foreign object is a bit different that in plain HTML so we wrap
      ;; inside a foreign object "dummy" so this awkward behaviour is take into account
      [:svg {:style {:top 0 :left 0 :position "fixed" :width "100%" :height "100%" :opacity (when-not (dbg/enabled? :html-text) 0)}}
       [:foreignObject {:x 0 :y 0 :width "100%" :height "100%"}
        [:div {:style {:pointer-events (when-not (dbg/enabled? :html-text) "none")
                       ;; some opacity because to debug auto-width events will fill the screen
                       :opacity 0.6}}
         (when (and (:can-edit permissions) (not read-only?))
           [:& stvh/viewport-texts
            {:key (dm/str "texts-" page-id)
             :page-id page-id
             :objects objects
             :modifiers modifiers
             :edition edition}])]]]

      (when show-comments?
        [:> comments/comments-layer* {:vbox vbox
                                      :page-id page-id
                                      :vport vport
                                      :zoom zoom}])

      (when picking-color?
        [:& pixel-overlay/pixel-overlay {:vport vport
                                         :vbox vbox
                                         :layout layout
                                         :viewport-ref viewport-ref}])]

     [:canvas {:id "render"
               :ref canvas-ref
               :class (stl/css :render-shapes)
               :key (dm/str "render" page-id)
               :width (* wasm.api/dpr (:width vport 0))
               :height (* wasm.api/dpr (:height vport 0))
               :style {:background-color background
                       :pointer-events "none"}}]

     [:svg.viewport-controls
      {:xmlns "http://www.w3.org/2000/svg"
       :xmlnsXlink "http://www.w3.org/1999/xlink"
       :preserveAspectRatio "xMidYMid meet"
       :key (str "viewport" page-id)
       :view-box (utils/format-viewbox vbox)
       :ref on-viewport-ref
       :class (dm/str @cursor (when drawing-tool " drawing") " " (stl/css :viewport-controls))
       :style {:touch-action "none"}
       :fill "none"

       :on-click         on-click
       :on-context-menu  on-context-menu
       :on-double-click  on-double-click
       :on-drag-enter    on-drag-enter
       :on-drag-over     on-drag-over
       :on-drag-end      on-drag-end
       :on-drop          on-drop
       :on-pointer-down  on-pointer-down
       :on-pointer-enter on-pointer-enter
       :on-pointer-leave on-pointer-leave
       :on-pointer-move  on-pointer-move
       :on-pointer-up    on-pointer-up}

      [:defs
       ;; This clip is so the handlers are not over the rulers
       [:clipPath {:id "clip-handlers"}
        [:rect {:x (+ (:x vbox) rule-area-size)
                :y (+ (:y vbox) rule-area-size)
                :width (max 0 (- (:width vbox) rule-area-size))
                :height (max 0 (- (:height vbox) rule-area-size))}]]]

      [:g {:style {:pointer-events (if disable-events? "none" "auto")}}
       (when show-text-editor?
         (if (features/active-feature? @st/state "text-editor/v2")
           [:& editor-v2/text-editor {:shape editing-shape
                                      :modifiers modifiers}]
           [:& editor-v1/text-editor-svg {:shape editing-shape
                                          :modifiers modifiers}]))

       (when show-frame-outline?
         (let [outlined-frame-id
               (->> @hover-ids
                    (filter #(cfh/frame-shape? (get base-objects %)))
                    (remove selected)
                    (last))
               outlined-frame (get objects outlined-frame-id)]
           [:*
            [:& outline/shape-outlines
             {:objects base-objects
              :hover #{outlined-frame-id}
              :zoom zoom
              :modifiers modifiers}]

            (when (ctl/any-layout? outlined-frame)
              [:g.ghost-outline
               [:& outline/shape-outlines
                {:objects base-objects
                 :selected selected
                 :zoom zoom}]])]))

       (when show-outlines?
         [:& outline/shape-outlines
          {:objects base-objects
           :selected selected
           :hover #{(:id @hover) @frame-hover}
           :highlighted highlighted
           :edition edition
           :zoom zoom
           :modifiers modifiers}])

       (when show-selection-handlers?
         [:& selection/selection-area
          {:shapes selected-shapes
           :zoom zoom
           :edition edition
           :disable-handlers (or drawing-tool edition @space? @mod?)
           :on-move-selected on-move-selected
           :on-context-menu on-menu-selected}])

       (when show-text-editor?
         [:& text-edition-outline
          {:shape (get base-objects edition)
           :zoom zoom
           :modifiers modifiers}])

       (when show-measures?
         [:& msr/measurement
          {:bounds vbox
           :selected-shapes selected-shapes
           :frame selected-frame
           :hover-shape @measure-hover
           :zoom zoom}])

       (when show-padding?
         [:& mfc/padding-control
          {:frame first-shape
           :hover @frame-hover
           :zoom zoom
           :alt? @alt?
           :shift? @shift?
           :on-move-selected on-move-selected
           :on-context-menu on-menu-selected}])

       (when show-padding?
         [:& mfc/gap-control
          {:frame first-shape
           :hover @frame-hover
           :zoom zoom
           :alt? @alt?
           :shift? @shift?
           :on-move-selected on-move-selected
           :on-context-menu on-menu-selected}])

       (when show-margin?
         [:& mfc/margin-control
          {:shape first-shape
           :parent selected-frame
           :hover @frame-hover
           :zoom zoom
           :alt? @alt?
           :shift? @shift?}])

       [:& widgets/frame-titles
        {:objects (with-meta objects-modified nil)
         :selected selected
         :zoom zoom
         :show-artboard-names? show-artboard-names?
         :on-frame-enter on-frame-enter
         :on-frame-leave on-frame-leave
         :on-frame-select on-frame-select
         :focus focus}]

       (when show-prototypes?
         [:> widgets/frame-flows*
          {:flows (:flows page)
           :objects objects-modified
           :selected selected
           :zoom zoom
           :on-frame-enter on-frame-enter
           :on-frame-leave on-frame-leave
           :on-frame-select on-frame-select}])

       (when show-draw-area?
         [:& drawarea/draw-area
          {:shape drawing-obj
           :zoom zoom
           :tool drawing-tool}])

       (when show-grids?
         [:& frame-grid/frame-grid
          {:zoom zoom
           :selected selected
           :transform transform
           :focus focus}])

       (when show-pixel-grid?
         [:& widgets/pixel-grid
          {:vbox vbox
           :zoom zoom}])

       (when show-snap-points?
         [:& snap-points/snap-points
          {:layout layout
           :transform transform
           :drawing drawing-obj
           :zoom zoom
           :page-id page-id
           :selected selected
           :objects objects-modified
           :focus focus}])

       (when show-snap-distance?
         [:& snap-distances/snap-distances
          {:layout layout
           :zoom zoom
           :transform transform
           :selected selected
           :selected-shapes selected-shapes
           :page-id page-id}])

       (when show-cursor-tooltip?
         [:& widgets/cursor-tooltip
          {:zoom zoom
           :tooltip tooltip}])

       (when show-selrect?
         [:& widgets/selection-rect {:data selrect
                                     :zoom zoom}])

       (when show-presence?
         [:& presence/active-cursors
          {:page-id page-id}])

       (when-not hide-ui?
         [:& rulers/rulers
          {:zoom zoom
           :zoom-inverse zoom-inverse
           :vbox vbox
           :selected-shapes selected-shapes
           :offset-x offset-x
           :offset-y offset-y
           :show-rulers? show-rulers?}])

       (when (and show-rulers? show-grids?)
         [:> guides/viewport-guides*
          {:zoom zoom
           :vbox vbox
           :guides (:guides page)
           :hover-frame guide-frame
           :disabled-guides disabled-guides?
           :modifiers modifiers}])

       ;; DEBUG LAYOUT DROP-ZONES
       (when (dbg/enabled? :layout-drop-zones)
         [:& wvd/debug-drop-zones {:selected-shapes selected-shapes
                                   :objects base-objects
                                   :hover-top-frame-id @hover-top-frame-id
                                   :zoom zoom}])

       (when (dbg/enabled? :layout-content-bounds)
         [:& wvd/debug-content-bounds {:selected-shapes selected-shapes
                                       :objects base-objects
                                       :hover-top-frame-id @hover-top-frame-id
                                       :zoom zoom}])

       (when (dbg/enabled? :layout-lines)
         [:& wvd/debug-layout-lines {:selected-shapes selected-shapes
                                     :objects base-objects
                                     :hover-top-frame-id @hover-top-frame-id
                                     :zoom zoom}])

       (when (dbg/enabled? :parent-bounds)
         [:& wvd/debug-parent-bounds {:selected-shapes selected-shapes
                                      :objects base-objects
                                      :hover-top-frame-id @hover-top-frame-id
                                      :zoom zoom}])

       (when (dbg/enabled? :grid-layout)
         [:& wvd/debug-grid-layout {:selected-shapes selected-shapes
                                    :objects base-objects
                                    :hover-top-frame-id @hover-top-frame-id
                                    :zoom zoom}])

       (when show-selection-handlers?
         [:g.selection-handlers {:clipPath "url(#clip-handlers)"}
          [:& selection/selection-handlers
           {:selected selected
            :shapes selected-shapes
            :zoom zoom
            :edition edition
            :disable-handlers (or drawing-tool edition @space?)}]

          (when show-prototypes?
            [:& interactions/interactions
             {:selected selected
              :page-id page-id
              :zoom zoom
              :objects objects-modified
              :current-transform transform
              :hover-disabled? hover-disabled?}])])

       (when show-gradient-handlers?
         [:> gradients/gradient-handlers*
          {:id (first selected)
           :zoom zoom}])

       [:g.grid-layout-editor {:clipPath "url(#clip-handlers)"}
        (when (or show-grid-editor? hover-grid?)
          [:& grid-layout/editor
           {:zoom zoom
            :objects base-objects
            :modifiers modifiers
            :shape (or (get base-objects edition)
                       (get base-objects @hover-top-frame-id))
            :view-only (not show-grid-editor?)}])

        (for [frame (ctt/get-frames objects)]
          (when (and (ctl/grid-layout? frame)
                     (empty? (:shapes frame))
                     (not= edition (:id frame))
                     (not= @hover-top-frame-id (:id frame)))
            [:& grid-layout/editor
             {:zoom zoom
              :key (dm/str (:id frame))
              :objects base-objects
              :modifiers modifiers
              :shape frame
              :view-only true}]))]
       [:g.scrollbar-wrapper {:clipPath "url(#clip-handlers)"}
        [:& scroll-bars/viewport-scrollbars
         {:objects base-objects
          :zoom zoom
          :vbox vbox
          :bottom-padding (when palete-size (+ palete-size 8))}]]]]]))
