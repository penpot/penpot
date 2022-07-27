;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
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
   [app.main.ui.workspace.viewport.drawarea :as drawarea]
   [app.main.ui.workspace.viewport.frame-grid :as frame-grid]
   [app.main.ui.workspace.viewport.gradients :as gradients]
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
   [app.main.ui.workspace.viewport.widgets :as widgets]
   [beicon.core :as rx]
   [debug :refer [debug?]]
   [rumext.alpha :as mf]))

;; --- Viewport

(mf/defc viewport
  [{:keys [wlocal wglobal selected layout file] :as props}]
  (let [;; When adding data from workspace-local revisit `app.main.ui.workspace` to check
        ;; that the new parameter is sent
        {:keys [edit-path
                panning
                selrect
                transform
                vbox
                vport
                zoom
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

        objects-modified  (mf/with-memo [base-objects modifiers]
                            (gsh/merge-modifiers base-objects modifiers))

        background        (get options :background clr/canvas)

        ;; STATE
        alt?              (mf/use-state false)
        mod?              (mf/use-state false)
        space?            (mf/use-state false)
        cursor            (mf/use-state (utils/get-cursor :pointer-inner))
        hover-ids         (mf/use-state nil)
        hover             (mf/use-state nil)
        hover-disabled?   (mf/use-state false)
        frame-hover       (mf/use-state nil)
        active-frames     (mf/use-state #{})

        ;; REFS
        viewport-ref      (mf/use-ref nil)
        overlays-ref      (mf/use-ref nil)

        ;; VARS
        disable-paste     (mf/use-var false)
        in-viewport?      (mf/use-var false)

        ;; STREAMS
        move-stream       (mf/use-memo #(rx/subject))

        frame-parent      (mf/use-memo
                           (mf/deps @hover-ids base-objects)
                           (fn []
                             (let [parent (get base-objects (last @hover-ids))]
                               (when (= :frame (:type parent))
                                 parent))))

        zoom              (d/check-num zoom 1)
        drawing-tool      (:tool drawing)
        drawing-obj       (:object drawing)

        selected-shapes   (into [] (keep (d/getf objects-modified)) selected)
        selected-frames   (into #{} (map :frame-id) selected-shapes)

        ;; Only when we have all the selected shapes in one frame
        selected-frame    (when (= (count selected-frames) 1) (get base-objects (first selected-frames)))

        editing-shape     (when edition (get base-objects edition))

        create-comment?   (= :comments drawing-tool)
        drawing-path?     (or (and edition (= :draw (get-in edit-path [edition :edit-mode])))
                              (and (some? drawing-obj) (= :path (:type drawing-obj))))
        node-editing?     (and edition (not= :text (get-in base-objects [edition :type])))
        text-editing?     (and edition (= :text (get-in base-objects [edition :type])))

        on-click          (actions/on-click hover selected edition drawing-path? drawing-tool space? selrect)
        on-context-menu   (actions/on-context-menu hover hover-ids)
        on-double-click   (actions/on-double-click hover hover-ids drawing-path? base-objects edition)
        on-drag-enter     (actions/on-drag-enter)
        on-drag-over      (actions/on-drag-over)
        on-drop           (actions/on-drop file viewport-ref zoom)
        on-mouse-down     (actions/on-mouse-down @hover selected edition drawing-tool text-editing? node-editing?
                                                 drawing-path? create-comment? space? viewport-ref zoom panning)
        on-mouse-up       (actions/on-mouse-up disable-paste)
        on-pointer-down   (actions/on-pointer-down)
        on-pointer-enter  (actions/on-pointer-enter in-viewport?)
        on-pointer-leave  (actions/on-pointer-leave in-viewport?)
        on-pointer-move   (actions/on-pointer-move viewport-ref zoom move-stream)
        on-pointer-up     (actions/on-pointer-up)
        on-move-selected  (actions/on-move-selected hover hover-ids selected space?)
        on-menu-selected  (actions/on-menu-selected hover hover-ids selected)

        on-frame-enter    (actions/on-frame-enter frame-hover)
        on-frame-leave    (actions/on-frame-leave frame-hover)
        on-frame-select   (actions/on-frame-select selected)

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
        show-measures?           (and (not transform) (not node-editing?) show-distances?)
        show-artboard-names?     (contains? layout :display-artboard-names)
        show-rules?              (and (contains? layout :rules) (not (contains? layout :hide-ui)))


        disabled-guides?         (or drawing-tool transform)]

    (hooks/setup-dom-events viewport-ref overlays-ref zoom disable-paste in-viewport?)
    (hooks/setup-viewport-size viewport-ref)
    (hooks/setup-cursor cursor alt? mod? space? panning drawing-tool drawing-path? node-editing?)
    (hooks/setup-keyboard alt? mod? space?)
    (hooks/setup-hover-shapes page-id move-stream base-objects transform selected mod? hover hover-ids @hover-disabled? focus zoom)
    (hooks/setup-viewport-modifiers modifiers base-objects)
    (hooks/setup-shortcuts node-editing? drawing-path?)
    (hooks/setup-active-frames base-objects hover-ids selected active-frames zoom transform vbox)

    [:div.viewport
     [:div.viewport-overlays {:ref overlays-ref}
      [:div {:style {:pointer-events "none" :opacity 0}}
       [:& stvh/viewport-texts
        {:key (dm/str "texts-" page-id)
         :page-id page-id
         :objects objects
         :modifiers modifiers
         :edition edition}]]

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
       :ref viewport-ref
       :class (when drawing-tool "drawing")
       :style {:cursor @cursor}
       :fill "none"

       :on-click         on-click
       :on-context-menu  on-context-menu
       :on-double-click  on-double-click
       :on-drag-enter    on-drag-enter
       :on-drag-over     on-drag-over
       :on-drop          on-drop
       :on-mouse-down    on-mouse-down
       :on-mouse-up      on-mouse-up
       :on-pointer-down  on-pointer-down
       :on-pointer-enter on-pointer-enter
       :on-pointer-leave on-pointer-leave
       :on-pointer-move  on-pointer-move
       :on-pointer-up    on-pointer-up}

      [:g {:style {:pointer-events (if disable-events? "none" "auto")}}
       (when show-text-editor?
         [:& editor/text-editor-svg {:shape editing-shape}])

       (when show-frame-outline?
         [:& outline/shape-outlines
          {:objects base-objects
           :hover #{(->> @hover-ids
                         (filter #(cph/frame-shape? base-objects %))
                         (remove selected)
                         (first))}
           :zoom zoom}])

       (when show-outlines?
         [:& outline/shape-outlines
          {:objects base-objects
           :selected selected
           :hover #{(:id @hover) @frame-hover}
           :edition edition
           :zoom zoom}])

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
           :zoom zoom}])

       (when show-measures?
         [:& msr/measurement
          {:bounds vbox
           :selected-shapes selected-shapes
           :frame selected-frame
           :hover-shape @hover
           :zoom zoom}])

       [:& widgets/frame-titles
        {:objects base-objects
         :selected selected
         :zoom zoom
         :show-artboard-names? show-artboard-names?
         :on-frame-enter on-frame-enter
         :on-frame-leave on-frame-leave
         :on-frame-select on-frame-select}]

       (when show-prototypes?
         [:& widgets/frame-flows
          {:flows (:flows options)
           :objects base-objects
           :selected selected
           :zoom zoom
           :modifiers modifiers
           :on-frame-enter on-frame-enter
           :on-frame-leave on-frame-leave
           :on-frame-select on-frame-select}])

       (when show-gradient-handlers?
         [:& gradients/gradient-handlers
          {:id (first selected)
           :zoom zoom}])

       (when show-draw-area?
         [:& drawarea/draw-area
          {:shape drawing-obj
           :zoom zoom
           :tool drawing-tool
           :modifiers modifiers}])

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
           :objects base-objects
           :focus focus
           :modifiers modifiers}])

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

       [:& widgets/viewport-actions]

       [:& scroll-bars/viewport-scrollbars
        {:objects base-objects
         :zoom zoom
         :vbox vbox
         :viewport-ref viewport-ref}]

       (when show-rules?
         [:& rules/rules
          {:zoom zoom
           :vbox vbox
           :selected-shapes selected-shapes}])

       (when show-rules?
         [:& guides/viewport-guides
          {:zoom zoom
           :vbox vbox
           :hover-frame frame-parent
           :modifiers modifiers
           :disabled-guides? disabled-guides?}])

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
              :zoom zoom
              :objects objects-modified
              :current-transform transform
              :hover-disabled? hover-disabled?}])])]]]))
