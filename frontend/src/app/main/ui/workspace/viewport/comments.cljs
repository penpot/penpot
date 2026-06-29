;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [rumext.v2 :as mf]))

;; Translation matrix from a frame to its transformed copy, matching
;; `move-frame-comment-threads` so the bubble doesn't jump when committed.
(defn- frame-translate-modifier
  [frame frame']
  (gmt/translate-matrix
   (gpt/to-vec (gpt/point (:x frame) (:y frame))
               (gpt/point (:x frame') (:y frame')))))

;; Position modifier for a frame transformed by the SVG (legacy) renderer.
(defn- svg-position-modifier
  [objects modifiers frame-id]
  (when-let [frame (get objects frame-id)]
    (when-let [modifier (get-in modifiers [frame-id :modifiers])]
      (frame-translate-modifier frame (gsh/transform-shape frame modifier)))))

;; Position modifier for a frame transformed by the WASM renderer.
(defn- wasm-position-modifier
  [objects wasm-mods frame-id]
  (when-let [frame (get objects frame-id)]
    (when-let [transform (get wasm-mods frame-id)]
      (frame-translate-modifier frame (gsh/apply-transform frame transform)))))

;; Per-frame subscription to the active transform modifiers, so a bubble moves
;; live with its frame during a drag. Scoped per frame to avoid re-rendering
;; the whole layer at animation-frame rate. Only one renderer is active, so at
;; most one of the two sources yields a modifier.
(defn- use-frame-position-modifier
  [frame-id]
  (let [modifiers (mf/deref refs/workspace-modifiers)
        wasm-mods (mf/deref refs/workspace-wasm-modifiers)
        objects   (mf/deref refs/workspace-page-objects)]
    (or (svg-position-modifier objects modifiers frame-id)
        (wasm-position-modifier objects wasm-mods frame-id))))

(mf/defc comment-floating-bubble-wrapper*
  {::mf/private true}
  [{:keys [thread zoom is-open]}]
  (let [position-modifier (use-frame-position-modifier (:frame-id thread))]
    [:> cmt/comment-floating-bubble*
     {:thread thread
      :zoom zoom
      :position-modifier position-modifier
      :is-open is-open}]))

(mf/defc comment-floating-group-wrapper*
  {::mf/private true}
  [{:keys [thread-group zoom]}]
  (let [thread            (first thread-group)
        position-modifier (use-frame-position-modifier (:frame-id thread))]
    [:> cmt/comment-floating-group*
     {:thread-group thread-group
      :zoom zoom
      :position-modifier position-modifier}]))

(mf/defc comment-floating-thread-wrapper*
  {::mf/private true}
  [{:keys [thread viewport zoom]}]
  (let [position-modifier (use-frame-position-modifier (:frame-id thread))]
    [:> cmt/comment-floating-thread*
     {:thread thread
      :viewport viewport
      :position-modifier position-modifier
      :zoom zoom}]))

(mf/defc comments-layer*
  {::mf/wrap [mf/memo]}
  [{:keys [vbox vport zoom file-id page-id]}]
  (let [vbox-x      (dm/get-prop vbox :x)
        vbox-y      (dm/get-prop vbox :y)
        vport-w     (dm/get-prop vport :width)
        vport-h     (dm/get-prop vport :height)

        pos-x       (* (- vbox-x) zoom)
        pos-y       (* (- vbox-y) zoom)

        profile     (mf/deref refs/profile)
        local       (mf/deref refs/comments-local)

        threads-map (mf/deref refs/threads)

        threads
        (mf/with-memo [threads-map local profile page-id]
          (->> (vals threads-map)
               (filter #(= (:page-id %) page-id))
               (dcm/apply-filters local profile)))

        viewport
        (assoc vport :offset-x pos-x :offset-y pos-y)

        on-draft-cancel
        (mf/use-fn #(st/emit! :interrupt))

        on-draft-submit
        (mf/use-fn
         (fn [draft]
           (st/emit! (dcm/create-thread-on-workspace draft))))]

    (mf/with-effect [file-id]
      (st/emit! (dwcm/initialize-comments file-id))
      (fn [] (st/emit! ::dwcm/finalize)))

    [:div {:class (stl/css :comments-section)}
     [:div
      {:id "comments"
       :class (stl/css :workspace-comments-container)
       :style {:width (dm/str vport-w "px")
               :height (dm/str vport-h "px")}}
      [:div {:class (stl/css :threads)
             :style {:transform (dm/fmt "translate(%px, %px)" pos-x pos-y)}}

       (for [thread-group (cmt/group-bubbles zoom threads)]
         (let [group? (> (count thread-group) 1)
               thread (first thread-group)]
           (if group?
             [:> comment-floating-group-wrapper* {:thread-group thread-group
                                                  :zoom zoom
                                                  :key (:seqn thread)}]
             [:> comment-floating-bubble-wrapper* {:thread thread
                                                   :zoom zoom
                                                   :is-open (= (:id thread) (:open local))
                                                   :key (:seqn thread)}])))

       (when-let [id (:open local)]
         (when-let [thread (get threads-map id)]
           (when (seq (dcm/apply-filters local profile [thread]))
             [:> comment-floating-thread-wrapper*
              {:thread thread
               :viewport viewport
               :zoom zoom}])))

       (when-let [draft (:draft local)]
         [:> cmt/comment-floating-thread-draft*
          {:draft draft
           :on-cancel on-draft-cancel
           :on-submit on-draft-submit
           :viewport viewport
           :zoom zoom}])]]]))
