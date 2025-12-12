;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.color :as clr]
   [app.common.types.component :as ctk]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.variants :as dwv]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.flex-controls :as mfc]
   [app.main.ui.hooks :as ui-hooks]
   [app.main.ui.measurements :as msr]
   [app.main.ui.shapes.export :as use]
   [app.main.ui.workspace.shapes :as shapes]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor*]]
   [app.main.ui.workspace.shapes.text.editor :as editor-v1]
   [app.main.ui.workspace.shapes.text.text-edition-outline :refer [text-edition-outline]]
   [app.main.ui.workspace.shapes.text.v2-editor :as editor-v2]
   [app.main.ui.workspace.shapes.text.viewport-texts-html :as stvh]
   [app.main.ui.workspace.top-toolbar :refer [top-toolbar*]]
   [app.main.ui.workspace.viewport-wasm :as viewport.wasm]
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
   [app.main.ui.workspace.viewport.top-bar :refer [grid-edition-bar* path-edition-bar* view-only-bar*]]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.main.ui.workspace.viewport.viewport-ref :refer [create-viewport-ref]]
   [app.main.ui.workspace.viewport.widgets :as widgets]
   [app.util.debug :as dbg]
   [beicon.v2.core :as rx]
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

(mf/defc viewport-classic*
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
                edition]}
        wlocal

        {:keys [options-mode
                tooltip
                show-distances?
                picking-color?]}
        wglobal

        vbox'             (mf/use-debounce 100 vbox)

        permissions       (mf/use-ctx ctx/permissions)
        read-only?        (mf/use-ctx ctx/workspace-read-only?)

        ;; DEREFS
        drawing           (mf/deref refs/workspace-drawing)
        focus             (mf/deref refs/workspace-focus-selected)

        file-id           (get file :id)
        page-id           (get page :id)
        objects           (get page :objects)
        background        (get page :background clr/canvas)

        base-objects      (ui-hooks/with-focus-objects objects focus)

        modifiers         (mf/deref refs/workspace-modifiers)
        text-modifiers    (mf/deref refs/workspace-text-modifier)

        objects-modified  (mf/with-memo [base-objects text-modifiers modifiers]
                            (apply-modifiers-to-selected selected base-objects text-modifiers modifiers))

        selected-shapes   (->> selected
                               (into [] (keep (d/getf objects-modified)))
                               (not-empty))

        ;; STATE
        alt?               (mf/use-state false)
        shift?             (mf/use-state false)
        mod?               (mf/use-state false)
        space?             (mf/use-state false)
        z?                 (mf/use-state false)
        cursor             (mf/use-state #(utils/get-cursor :pointer-inner))
        hover-ids          (mf/use-state nil)
        hover              (mf/use-state nil)
        measure-hover      (mf/use-state nil)
        hover-disabled?    (mf/use-state false)
        hover-top-frame-id (mf/use-state nil)
        frame-hover        (mf/use-state nil)
        active-frames      (mf/use-state #{})

        ;; REFS
        [viewport-ref on-viewport-ref]
        (create-viewport-ref)

        canvas-ref        (mf/use-ref nil)

        ;; STATE REFS
        disable-paste-ref (mf/use-ref false)
        in-viewport-ref   (mf/use-ref false)

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
        selected-frame    (when (= (count selected-frames) 1)
                            (get base-objects (first selected-frames)))

        edit-path-state   (get edit-path edition)
        edit-path-mode    (get edit-path-state :edit-mode)

        path-editing?     (some? edit-path-state)
        path-drawing?     (or (= edit-path-mode :draw)
                              (and (= :path (get drawing-obj :type))
                                   (not= :curve drawing-tool)))

        editing-shape     (when edition
                            (get base-objects edition))

        editing-shape     (mf/with-memo [editing-shape path-editing? base-objects]
                            (if path-editing?
                              (path/convert-to-path editing-shape base-objects)
                              editing-shape))

        create-comment?   (= :comments drawing-tool)

        text-editing?     (cfh/text-shape? editing-shape)
        grid-editing?     (and edition (ctl/grid-layout? base-objects edition))

        mode-inspect?     (= options-mode :inspect)

        on-click          (actions/on-click hover selected edition path-drawing? drawing-tool space? selrect z?)
        on-context-menu   (actions/on-context-menu hover hover-ids read-only?)
        on-double-click   (actions/on-double-click hover hover-ids hover-top-frame-id path-drawing? base-objects edition drawing-tool z? read-only?)

        comp-inst-ref     (mf/use-ref false)
        on-drag-enter     (actions/on-drag-enter comp-inst-ref)
        on-drag-over      (actions/on-drag-over move-stream)
        on-drag-end       (actions/on-drag-over comp-inst-ref)
        on-drop           (actions/on-drop file comp-inst-ref)
        on-pointer-down   (actions/on-pointer-down @hover selected edition drawing-tool text-editing? path-editing? grid-editing?
                                                   path-drawing? create-comment? space? panning z? read-only?)

        on-pointer-up     (actions/on-pointer-up disable-paste-ref)

        on-pointer-enter  (actions/on-pointer-enter in-viewport-ref)
        on-pointer-leave  (actions/on-pointer-leave in-viewport-ref)
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
                                      (not (#{:path :curve} drawing-tool)))

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
                                      (not path-editing?)
                                      (or show-distances? mode-inspect? read-only?))
        show-artboard-names?     (contains? layout :display-artboard-names)
        hide-ui?                 (contains? layout :hide-ui)
        show-rulers?             (and (contains? layout :rulers) (not hide-ui?))


        disabled-guides?         (or drawing-tool transform path-drawing? path-editing? @space? @mod?)

        single-select?           (= (count selected-shapes) 1)

        first-shape              (first selected-shapes)

        show-add-variant?        (and single-select?
                                      (or (ctk/is-variant-container? first-shape)
                                          (ctk/is-variant? first-shape)))

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

        add-variant
        (mf/use-fn
         (mf/deps first-shape)
         #(st/emit!
           (dwv/add-new-variant (:id first-shape))))]

    (hooks/setup-dom-events zoom disable-paste-ref in-viewport-ref read-only? drawing-tool path-drawing?)
    (hooks/setup-viewport-size vport viewport-ref)
    (hooks/setup-cursor cursor alt? mod? space? panning drawing-tool path-drawing? path-editing? z? read-only?)
    (hooks/setup-keyboard alt? mod? space? z? shift?)
    (hooks/setup-hover-shapes page-id move-stream base-objects transform selected mod? hover measure-hover
                              hover-ids hover-top-frame-id @hover-disabled? focus zoom show-measures?)
    (hooks/setup-viewport-modifiers modifiers base-objects)
    (hooks/setup-shortcuts path-editing? path-drawing? text-editing? grid-editing?)
    (hooks/setup-active-frames base-objects hover-ids selected active-frames zoom transform vbox)

    [:div {:class (stl/css :viewport) :style #js {"--zoom" zoom} :data-testid "viewport"}
     (when (:can-edit permissions)
       (if read-only?
         [:> view-only-bar* {}]
         [:*
          (when-not hide-ui?
            [:> top-toolbar* {:layout layout}])

          (when (and ^boolean path-editing?
                     ^boolean single-select?)
            [:> path-edition-bar* {:shape editing-shape
                                   :edit-path-state edit-path-state
                                   :layout layout}])

          (when (and ^boolean grid-editing?
                     ^boolean single-select?)
            [:> grid-edition-bar* {:shape editing-shape}])]))

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
                                      :file-id file-id
                                      :vport vport
                                      :zoom zoom}])

      (when picking-color?
        [:& pixel-overlay/pixel-overlay {:vport vport
                                         :vbox vbox
                                         :layout layout
                                         :viewport-ref viewport-ref}])]

     [:svg
      {:id "render"
       :class (stl/css :render-shapes)
       :xmlns "http://www.w3.org/2000/svg"
       :xmlnsXlink "http://www.w3.org/1999/xlink"
       :xmlns:penpot "https://penpot.app/xmlns"
       :preserveAspectRatio "xMidYMid meet"
       :key (str "render" page-id)
       :width (:width vport 0)
       :height (:height vport 0)
       :view-box (utils/format-viewbox vbox)
       :style {:background-color background
               :pointer-events "none"}
       :fill "none"}

      [:defs
       [:linearGradient {:id "frame-placeholder-gradient"}
        [:animateTransform
         {:attributeName "gradientTransform"
          :type "translate"
          :from "-1 0"
          :to "1 0"
          :dur "2s"
          :repeatCount "indefinite"}]
        [:stop {:offset "0%" :stop-color (str "color-mix(in srgb-linear, " background " 90%, #777)") :stop-opacity 1}]
        [:stop {:offset "50%" :stop-color (str "color-mix(in srgb-linear, " background " 80%, #777)") :stop-opacity 1}]
        [:stop {:offset "100%" :stop-color (str "color-mix(in srgb-linear, " background " 90%, #777)") :stop-opacity 1}]]]

      (when (dbg/enabled? :show-export-metadata)
        [:& use/export-page {:page page}])

      ;; We need a "real" background shape so layer transforms work properly in firefox
      [:rect {:width (:width vbox 0)
              :height (:height vbox 0)
              :x (:x vbox 0)
              :y (:y vbox 0)
              :fill background}]

      [:& (mf/provider ctx/current-vbox) {:value vbox'}
       [:& (mf/provider use/include-metadata-ctx) {:value (dbg/enabled? :show-export-metadata)}
        ;; Render root shape
        [:& shapes/root-shape {:key page-id
                               :objects base-objects
                               :active-frames @active-frames}]]]]

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
                                      :canvas-ref canvas-ref
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

       (when (and show-selection-handlers?
                  selected-shapes)
         [:> selection/area*
          {:shapes selected-shapes
           :zoom zoom
           :edition edition
           :disabled (or drawing-tool edition @space? @mod?)
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

       ;; Show distances during movement with ALT
       (when (and (= transform :move) @alt? (seq selected-shapes))
         [:& msr/measurement
          {:bounds vbox
           :selected-shapes selected-shapes
           :frame selected-frame
           :hover-shape @hover
           :zoom zoom}])

       ;; Reactive subscription to duplication relation (safe)
       (let [state-var (mf/use-var (resolve 'app.main.store/state))
             duplicated-info (get-in @(deref state-var) [:workspace-local :duplicated])]
         (when (and (= transform :move) @alt? duplicated-info)
           [:g.duplicated-distance
            [:& msr/distance-display
             {:from (get duplicated-info :selrect-original)
              :to (get duplicated-info :selrect-duplicated)
              :zoom zoom
              :bounds vbox}]]))

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

       [:> widgets/frame-titles*
        {:objects base-objects
         :selected selected
         :zoom zoom
         :is-show-artboard-names show-artboard-names?
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

       (when (and ^boolean show-draw-area?
                  ^boolean (cts/shape? drawing-obj))
         [:> drawarea/draw-area*
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
         [:> widgets/pixel-grid* {:vbox vbox
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
         [:> widgets/cursor-tooltip* {:zoom zoom
                                      :tooltip tooltip}])

       (when show-selrect?
         [:> widgets/selection-rect* {:data selrect
                                      :zoom zoom}])

       (when show-add-variant?
         [:> widgets/button-add* {:shape first-shape
                                  :zoom zoom
                                  :on-click add-variant}])

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
          (when-not text-editing?
            (if (and editing-shape path-editing?)
              [:> path-editor* {:shape editing-shape
                                :state edit-path-state
                                :zoom zoom}]
              (when selected-shapes
                [:> selection/handlers*
                 {:selected selected
                  :shapes selected-shapes
                  :zoom zoom
                  :disabled (or drawing-tool @space?)}])))

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

(mf/defc viewport*
  [props]
  (let [wasm-renderer-enabled? (features/use-feature "render-wasm/v1")]
    (if ^boolean wasm-renderer-enabled?
      [:> viewport.wasm/viewport* props]
      [:> viewport-classic* props])))
