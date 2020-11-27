;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text
  (:require
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.workspace.effects :as we]
   [app.main.ui.workspace.shapes.common :as common]
   [app.main.ui.workspace.shapes.text.editor :as editor]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

;; --- Events

(defn use-double-click [{:keys [id]} selected?]
  (mf/use-callback
   (mf/deps id selected?)
   (fn [event]
     (dom/stop-propagation event)
     (dom/prevent-default event)
     (when selected?
       (st/emit! (dw/start-edition-mode id))))))

;; --- Text Wrapper for workspace

(defn handle-shape-resize [{:keys [name id selrect grow-type overflow-text]} new-width new-height]
  (let [{shape-width :width shape-height :height} selrect
        undo-transaction (get-in @st/state [:workspace-undo :transaction])]
    (when (not undo-transaction) (st/emit! dwc/start-undo-transaction))
    (when (and (> new-width 0)
               (> new-height 0)
               (or (not= shape-width new-width)
                   (not= shape-height new-height)))
      (cond
        (and overflow-text (not= :fixed grow-type))
        (st/emit! (dwt/update-overflow-text id false))

        (and (= :fixed grow-type) (not overflow-text) (> new-height shape-height))
        (st/emit! (dwt/update-overflow-text id true))

        (and (= :fixed grow-type) overflow-text (<= new-height shape-height))
        (st/emit! (dwt/update-overflow-text id false))

        (= grow-type :auto-width)
        (st/emit! (dw/update-dimensions [id] :width new-width)
                  (dw/update-dimensions [id] :height new-height))

        (= grow-type :auto-height)
        (st/emit! (dw/update-dimensions [id] :height new-height))))
    (when (not undo-transaction) (st/emit! dwc/discard-undo-transaction))))

(defn resize-observer [shape root query]
  (mf/use-effect
   (mf/deps shape root query)
   (fn []
     (let [on-change (fn [entries]
                       (when (seq entries)
                         ;; RequestAnimationFrame so the "loop limit error" error is not thrown
                         ;; https://stackoverflow.com/questions/49384120/resizeobserver-loop-limit-exceeded
                         (timers/raf
                          #(let [width  (obj/get-in entries [0 "contentRect" "width"])
                                 height (obj/get-in entries [0 "contentRect" "height"])]
                             (handle-shape-resize shape width height)))))
           observer (js/ResizeObserver. on-change)
           node (when root (dom/query root query))]
       (when node (.observe observer node))
       #(.disconnect observer)))))

(mf/defc text-wrapper
  {::mf/wrap-props false}
  [props]
  (let [{:keys [id x y width height] :as shape} (unchecked-get props "shape")
        selected-iref (mf/use-memo (mf/deps (:id shape))
                                   #(refs/make-selected-ref (:id shape)))
        selected? (mf/deref selected-iref)
        edition   (mf/deref refs/selected-edition)
        current-transform (mf/deref refs/current-transform)

        render-editor (mf/use-state false)

        edition?  (= edition id)

        embed-resources? (mf/use-ctx muc/embed-ctx)

        handle-mouse-down (we/use-mouse-down shape)
        handle-context-menu (we/use-context-menu shape)
        handle-pointer-enter (we/use-pointer-enter shape)
        handle-pointer-leave (we/use-pointer-leave shape)
        handle-double-click (use-double-click shape selected?)

        text-ref (mf/use-ref nil)
        text-node (mf/ref-val text-ref)
        edit-text-ref (mf/use-ref nil)
        edit-text-node (mf/ref-val edit-text-ref)]

    (resize-observer shape text-node ".paragraph-set")
    (resize-observer shape edit-text-node ".paragraph-set")

    [:> shape-container {:shape shape}
     [:& text/text-shape {:key "text-shape"
                          :ref text-ref
                          :shape shape
                          :selected? selected?
                          :style {:display (when edition? "none")}}]
     (when edition?
       [:& editor/text-shape-edit {:key "editor"
                                   :ref edit-text-ref
                                   :shape shape}])

     (when-not edition?
       [:rect.text-actions
        {:x x
         :y y
         :width width
         :height height
         :style {:fill "transparent"}
         :on-mouse-down handle-mouse-down
         :on-context-menu handle-context-menu
         :on-pointer-enter handle-pointer-enter
         :on-pointer-leave handle-pointer-leave
         :on-double-click handle-double-click
         :transform (gsh/transform-matrix shape)}])]))

