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

        ;; Active transform modifiers (e.g. while dragging a board). We use
        ;; them to move comment bubbles live alongside their frame, instead of
        ;; only repositioning them at drop time. The SVG (legacy) renderer keeps
        ;; them in `:workspace-modifiers`, while the WASM renderer pushes them
        ;; through the `wasm-modifiers` stream as plain transform matrices.
        modifiers   (mf/deref refs/workspace-modifiers)
        wasm-mods   (into {} (mf/deref refs/workspace-wasm-modifiers))
        objects     (mf/deref refs/workspace-page-objects)

        threads
        (mf/with-memo [threads-map local profile page-id]
          (->> (vals threads-map)
               (filter #(= (:page-id %) page-id))
               (dcm/apply-filters local profile)))

        ;; Returns the position translation matrix for a frame that is being
        ;; transformed, or nil when the frame has no active modifier. The delta
        ;; matches `move-frame-comment-threads` (frame top-left displacement) so
        ;; the bubble does not jump when the modifier is committed.
        frame-position-modifier
        (fn [frame-id]
          (when-let [frame (get objects frame-id)]
            (let [frame'
                  (if-let [modifier (get-in modifiers [frame-id :modifiers])]
                    (gsh/transform-shape frame modifier)
                    (when-let [transform (get wasm-mods frame-id)]
                      (gsh/apply-transform frame transform)))]
              (when (some? frame')
                (let [delta (gpt/to-vec (gpt/point (:x frame) (:y frame))
                                        (gpt/point (:x frame') (:y frame')))]
                  (gmt/translate-matrix delta))))))

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
             [:> cmt/comment-floating-group* {:thread-group thread-group
                                              :zoom zoom
                                              :position-modifier (frame-position-modifier (:frame-id thread))
                                              :key (:seqn thread)}]
             [:> cmt/comment-floating-bubble* {:thread thread
                                               :zoom zoom
                                               :position-modifier (frame-position-modifier (:frame-id thread))
                                               :is-open (= (:id thread) (:open local))
                                               :key (:seqn thread)}])))

       (when-let [id (:open local)]
         (when-let [thread (get threads-map id)]
           (when (seq (dcm/apply-filters local profile [thread]))
             [:> cmt/comment-floating-thread*
              {:thread thread
               :viewport viewport
               :position-modifier (frame-position-modifier (:frame-id thread))
               :zoom zoom}])))

       (when-let [draft (:draft local)]
         [:> cmt/comment-floating-thread-draft*
          {:draft draft
           :on-cancel on-draft-cancel
           :on-submit on-draft-submit
           :viewport viewport
           :zoom zoom}])]]]))
