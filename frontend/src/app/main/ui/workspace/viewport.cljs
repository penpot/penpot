;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.refs :as refs]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as ui-hooks]
   [app.main.ui.measurements :as msr]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.export :as use]
   [app.main.ui.workspace.shapes :as shapes]
   [app.main.ui.workspace.shapes.text.editor :as editor]
   [app.main.ui.workspace.shapes.text.text-edition-outline :refer [text-edition-outline]]
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
   [app.main.ui.workspace.viewport.rules :as rules]
   [app.main.ui.workspace.viewport.scroll-bars :as scroll-bars]
   [app.main.ui.workspace.viewport.selection :as selection]
   [app.main.ui.workspace.viewport.snap-distances :as snap-distances]
   [app.main.ui.workspace.viewport.snap-points :as snap-points]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.main.ui.workspace.viewport.viewport-ref :refer [create-viewport-ref]]
   [app.main.ui.workspace.viewport.widgets :as widgets]
   [beicon.core :as rx]
   [debug :refer [debug?]]
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
          (and (cph/text-shape? shape) (contains? text-modifiers id))
          (dwm/apply-text-modifier (get text-modifiers id))

          (contains? modifiers id)
          (gsh/transform-shape (dm/get-in modifiers [id :modifiers]))))))

   objects
   selected))

(mf/defc viewport
  [{:keys [wlocal wglobal selected layout file] :as props}]
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

        ;; CONTEXT
        page-id           (mf/use-ctx ctx/current-page-id)

        ;; DEREFS
        drawing           (mf/deref refs/workspace-drawing)
        options           (mf/deref refs/workspace-page-options)
        focus             (mf/deref refs/workspace-focus-selected)

        objects-ref       (mf/use-memo #(refs/workspace-page-objects-by-id page-id))
        objects           (mf/deref objects-ref)
        base-objects      (-> objects (ui-hooks/with-focus-objects focus))

        modifiers         (mf/deref refs/workspace-modifiers)
        text-modifiers    (mf/deref refs/workspace-text-modifier)

        objects-modified  (mf/with-memo
                            [base-objects text-modifiers modifiers]
                            (apply-modifiers-to-selected selected base-objects text-modifiers modifiers))

        selected-shapes   (->> selected (keep (d/getf objects-modified)))

        background        (get options :background clr/canvas)

        ;; STATE
        alt?              (mf/use-state false)
        shift?            (mf/use-state false)
        mod?              (mf/use-state false)
        space?            (mf/use-state false)
        z?                (mf/use-state false)
        cursor            (mf/use-state (utils/get-cursor :pointer-inner))
        hover-ids         (mf/use-state nil)
        hover             (mf/use-state nil)
        hover-disabled?   (mf/use-state false)
        hover-top-frame-id (mf/use-state nil)
        frame-hover       (mf/use-state nil)
        active-frames     (mf/use-state #{})

        ;; REFS
        [viewport-ref
         on-viewport-ref] (create-viewport-ref)

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
                                        (d/seek (partial cph/root-frame? base-objects)))]
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
        node-editing?     (and edition (not= :text (get-in base-objects [edition :type])))
        text-editing?     (and edition (= :text (get-in base-objects [edition :type])))
        grid-editing?     (and edition (ctl/grid-layout? base-objects edition))

        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        mode-inspect?       (= options-mode :inspect)

        on-click          (actions/on-click hover selected edition drawing-path? drawing-tool space? selrect z?)
        on-context-menu   (actions/on-context-menu hover hover-ids workspace-read-only?)
        on-double-click   (actions/on-double-click hover hover-ids drawing-path? base-objects edition drawing-tool z? workspace-read-only?)
        on-drag-enter     (actions/on-drag-enter)
        on-drag-over      (actions/on-drag-over)
        on-drop           (actions/on-drop file)
        on-pointer-down   (actions/on-pointer-down @hover selected edition drawing-tool text-editing? node-editing? grid-editing?
                                                   drawing-path? create-comment? space? panning z? workspace-read-only?)

        on-pointer-up     (actions/on-pointer-up disable-paste)

        on-pointer-enter  (actions/on-pointer-enter in-viewport?)
        on-pointer-leave  (actions/on-pointer-leave in-viewport?)
        on-pointer-move   (actions/on-pointer-move move-stream)
        on-move-selected  (actions/on-move-selected hover hover-ids selected space? z? workspace-read-only?)
        on-menu-selected  (actions/on-menu-selected hover hover-ids selected workspace-read-only?)

        on-frame-enter    (actions/on-frame-enter frame-hover)
        on-frame-leave    (actions/on-frame-leave frame-hover)
        on-frame-select   (actions/on-frame-select selected workspace-read-only?)

        disable-events?          (contains? layout :comments)
        show-comments?           (= drawing-tool :comments)
        show-cursor-tooltip?     tooltip
        show-draw-area?          drawing-obj
        show-gradient-handlers?  (= (count selected) 1)
        show-grids?              (contains? layout :display-grid)

        show-frame-outline?      (= transform :move)
        show-outlines?           (and (nil? transform)
                                      (not edition)
                                      (not drawing-obj)
                                      (not (#{:comments :path :curve} drawing-tool)))

        show-pixel-grid?         (and (contains? layout :show-pixel-grid)
                                      (>= zoom 8))
        show-text-editor?        (and editing-shape (= :text (:type editing-shape)))
        show-grid-editor?        (and editing-shape (ctl/grid-layout? editing-shape))
        show-presence?           page-id
        show-prototypes?         (= options-mode :prototype)
        show-selection-handlers? (and (seq selected) (not show-text-editor?))
        show-snap-distance?      (and (contains? layout :dynamic-alignment)
                                      (= transform :move)
                                      (seq selected))
        show-snap-points?        (and (or (contains? layout :dynamic-alignment)
                                          (contains? layout :snap-grid))
                                      (or drawing-obj transform))
        show-selrect?            (and selrect (empty? drawing) (not text-editing?))
        show-measures?           (and (not transform)
                                      (not node-editing?)
                                      (or show-distances? mode-inspect?))
        show-artboard-names?     (contains? layout :display-artboard-names)
        show-rules?              (and (contains? layout :rules) (not (contains? layout :hide-ui)))


        disabled-guides?         (or drawing-tool transform)

        one-selected-shape?      (= (count selected-shapes) 1)

        show-padding? (and (nil? transform)
                           one-selected-shape?
                           (= (:type (first selected-shapes)) :frame)
                           (= (:layout (first selected-shapes)) :flex)
                           (zero? (:rotation (first selected-shapes))))


        show-margin? (and (nil? transform)
                          one-selected-shape?
                          (= (:layout selected-frame) :flex)
                          (zero? (:rotation (first selected-shapes))))

        first-selected-shape (first selected-shapes)
        selecting-first-level-frame? (and one-selected-shape?
                                          (cph/root-frame? first-selected-shape))

        offset-x (if selecting-first-level-frame?
                   (:x first-selected-shape)
                   (:x selected-frame))


        offset-y (if selecting-first-level-frame?
                   (:y (first selected-shapes))
                   (:y selected-frame))]

    (hooks/setup-dom-events zoom disable-paste in-viewport? workspace-read-only?)
    (hooks/setup-viewport-size vport viewport-ref)
    (hooks/setup-cursor cursor alt? mod? space? panning drawing-tool drawing-path? node-editing? z? workspace-read-only?)
    (hooks/setup-keyboard alt? mod? space? z? shift?)
    (hooks/setup-hover-shapes page-id move-stream base-objects transform selected mod? hover hover-ids hover-top-frame-id @hover-disabled? focus zoom show-measures?)
    (hooks/setup-viewport-modifiers modifiers base-objects)
    (hooks/setup-shortcuts node-editing? drawing-path? text-editing?)
    (hooks/setup-active-frames base-objects hover-ids selected active-frames zoom transform vbox)

    [:div.viewport
     [:div.viewport-overlays
      ;; The behaviour inside a foreign object is a bit different that in plain HTML so we wrap
      ;; inside a foreign object "dummy" so this awkward behaviour is take into account
      [:svg {:style {:top 0 :left 0 :position "fixed" :width "100%" :height "100%" :opacity (when-not (debug? :html-text) 0)}}
       [:foreignObject {:x 0 :y 0 :width "100%" :height "100%"}
        [:div {:style {:pointer-events (when-not (debug? :html-text) "none")
                       ;; some opacity because to debug auto-width events will fill the screen
                       :opacity 0.6}}
         [:& stvh/viewport-texts
          {:key (dm/str "texts-" page-id)
           :page-id page-id
           :objects objects
           :modifiers modifiers
           :edition edition}]]]]

      (when show-comments?
        [:& comments/comments-layer {:vbox vbox
                                     :vport vport
                                     :zoom zoom
                                     :drawing drawing
                                     :page-id page-id
                                     :file-id (:id file)}])

      (when picking-color?
        [:& pixel-overlay/pixel-overlay {:vport vport
                                         :vbox vbox
                                         :options options
                                         :layout layout
                                         :viewport-ref viewport-ref}])

      [:& widgets/viewport-actions]]

     [:svg.render-shapes
      {:id "render"
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

      (when (debug? :show-export-metadata)
        [:& use/export-page {:options options}])

      ;; We need a "real" background shape so layer transforms work properly in firefox
      [:rect {:width (:width vbox 0)
              :height (:height vbox 0)
              :x (:x vbox 0)
              :y (:y vbox 0)
              :fill background}]

      [:& (mf/provider use/include-metadata-ctx) {:value (debug? :show-export-metadata)}
       [:& (mf/provider embed/context) {:value true}
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
       :class (when drawing-tool "drawing")
       :style {:cursor @cursor :touch-action "none"}
       :fill "none"

       :on-click         on-click
       :on-context-menu  on-context-menu
       :on-double-click  on-double-click
       :on-drag-enter    on-drag-enter
       :on-drag-over     on-drag-over
       :on-drop          on-drop
       :on-pointer-down  on-pointer-down
       :on-pointer-enter on-pointer-enter
       :on-pointer-leave on-pointer-leave
       :on-pointer-move  on-pointer-move
       :on-pointer-up    on-pointer-up}

      [:g {:style {:pointer-events (if disable-events? "none" "auto")}}
       (when show-text-editor?
         [:& editor/text-editor-svg {:shape editing-shape
                                     :modifiers modifiers}])

       (when show-frame-outline?
         (let [outlined-frame-id
               (->> @hover-ids
                    (filter #(cph/frame-shape? (get base-objects %)))
                    (remove selected)
                    (first))
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
           :hover-shape @hover
           :zoom zoom}])

       (when show-padding?
         [:*
          [:& msr/padding
           {:frame (first selected-shapes)
            :hover @frame-hover
            :zoom zoom
            :alt? @alt?
            :shift? @shift?}]

          [:& msr/gap
           {:frame (first selected-shapes)
            :hover @frame-hover
            :zoom zoom
            :alt? @alt?
            :shift? @shift?}]])

       (when show-margin?
         [:& msr/margin
          {:shape (first selected-shapes)
           :parent selected-frame
           :hover @frame-hover
           :zoom zoom
           :alt? @alt?
           :shift? @shift?}])

       [:& widgets/frame-titles
        {:objects base-objects
         :selected selected
         :zoom zoom
         :show-artboard-names? show-artboard-names?
         :on-frame-enter on-frame-enter
         :on-frame-leave on-frame-leave
         :on-frame-select on-frame-select
         :focus focus}]

       (when show-prototypes?
         [:& widgets/frame-flows
          {:flows (:flows options)
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

       [:& scroll-bars/viewport-scrollbars
        {:objects base-objects
         :zoom zoom
         :vbox vbox}]

       (when show-rules?
         [:& rules/rules
          {:zoom zoom
           :zoom-inverse zoom-inverse
           :vbox vbox
           :selected-shapes selected-shapes
           :offset-x offset-x
           :offset-y offset-y}])

       (when show-rules?
         [:& guides/viewport-guides
          {:zoom zoom
           :vbox vbox
           :hover-frame guide-frame
           :disabled-guides? disabled-guides?
           :modifiers modifiers}])

       ;; DEBUG LAYOUT DROP-ZONES
       (when (debug? :layout-drop-zones)
         [:& wvd/debug-drop-zones {:selected-shapes selected-shapes
                                   :objects base-objects
                                   :hover-top-frame-id @hover-top-frame-id
                                   :zoom zoom}])

       (when (debug? :layout-content-bounds)
         [:& wvd/debug-content-bounds {:selected-shapes selected-shapes
                                       :objects base-objects
                                       :hover-top-frame-id @hover-top-frame-id
                                       :zoom zoom}])

       (when (debug? :layout-lines)
         [:& wvd/debug-layout-lines {:selected-shapes selected-shapes
                                     :objects base-objects
                                     :hover-top-frame-id @hover-top-frame-id
                                     :zoom zoom}])

       (when (debug? :parent-bounds)
         [:& wvd/debug-parent-bounds {:selected-shapes selected-shapes
                                      :objects base-objects
                                      :hover-top-frame-id @hover-top-frame-id
                                      :zoom zoom}])

       (when (debug? :grid-layout)
         [:& wvd/debug-grid-layout {:selected-shapes selected-shapes
                                    :objects base-objects
                                    :hover-top-frame-id @hover-top-frame-id
                                    :zoom zoom}])

       (when show-selection-handlers?
         [:g.selection-handlers {:clipPath "url(#clip-handlers)"}
          [:defs
           (let [rule-area-size (/ rules/rule-area-size zoom)]
             ;; This clip is so the handlers are not over the rules
             [:clipPath {:id "clip-handlers"}
              [:rect {:x (+ (:x vbox) rule-area-size)
                      :y (+ (:y vbox) rule-area-size)
                      :width (- (:width vbox) (* rule-area-size 2))
                      :height (- (:height vbox) (* rule-area-size 2))}]])]

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
         [:& gradients/gradient-handlers
          {:id (first selected)
           :zoom zoom}])

       (when show-grid-editor?
         [:& grid-layout/editor
          {:zoom zoom
           :objects base-objects
           :shape (get base-objects edition)}])]]]))
