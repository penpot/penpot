;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.workspace.shapes.common :as common]
   [app.util.dom :as dom]
   [app.util.logging :as log]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [app.util.text-editor :as ted]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

;; --- Text Wrapper for workspace

(mf/defc text-static-content
  [{:keys [shape]}]
  [:& text/text-shape {:shape shape
                       :grow-type (:grow-type shape)}])

(mf/defc text-resize-content
  {::mf/wrap-props false}
  [props]
  (let [{:keys [id name x y grow-type] :as shape} (obj/get props "shape")

        state-map     (mf/deref refs/workspace-editor-state)
        editor-state  (get state-map id)

        shape         (cond-> shape
                        (some? editor-state)
                        (assoc :content (-> editor-state
                                            (ted/get-editor-current-content)
                                            (ted/export-content))))

        paragraph-ref (mf/use-state nil)

        handle-resize-text
        (mf/use-callback
         (mf/deps id)
         (fn [entries]
           (when (seq entries)
             ;; RequestAnimationFrame so the "loop limit error" error is not thrown
             ;; https://stackoverflow.com/questions/49384120/resizeobserver-loop-limit-exceeded
             (timers/raf
              #(let [width  (obj/get-in entries [0 "contentRect" "width"])
                     height (obj/get-in entries [0 "contentRect" "height"])]
                 (when (and (not (mth/almost-zero? width)) (not (mth/almost-zero? height)))
                   (do (log/debug :msg "Resize detected" :shape-id id :width width :height height)
                       (st/emit! (dwt/resize-text id (mth/ceil width) (mth/ceil height))))))))))

        text-ref-cb
        (mf/use-callback
         (mf/deps handle-resize-text)
         (fn [node]
           (when node
             (let [obs-ref (atom nil)]
               (timers/schedule
                (fn []
                  (when-let [ps-node (dom/query node ".paragraph-set")]
                    (reset! paragraph-ref ps-node))))))))]

    (mf/use-effect
     (mf/deps @paragraph-ref handle-resize-text grow-type)
     (fn []
       (when-let [paragraph-node @paragraph-ref]
         (let [observer (js/ResizeObserver. handle-resize-text)]
           (log/debug :msg "Attach resize observer" :shape-id id :shape-name name)
           (.observe observer paragraph-node)
           #(.disconnect observer)))))

    [:& text/text-shape {:ref text-ref-cb
                         :shape shape}]))

(mf/defc text-wrapper
  {::mf/wrap-props false}
  [props]
  (let [{:keys [id x y width height] :as shape} (unchecked-get props "shape")
        edition  (mf/deref refs/selected-edition)
        edition? (= edition id)]

    [:> shape-container {:shape shape}
     ;; We keep hidden the shape when we're editing so it keeps track of the size
     ;; and updates the selrect acordingly
     [:g.text-shape {:opacity (when edition? 0)
                     :pointer-events "none"}

      [:& text-resize-content {:shape shape}]]]))
