;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [rumext.v2 :as mf]))

;; Pin transform for the bubble's frame so it follows the frame during a drag,
;; scoped per frame to avoid re-rendering the whole layer each tick.
(defn- use-frame-position-modifier
  [frame-id]
  (let [modifiers (mf/deref refs/workspace-modifiers)
        wasm-mods (mf/deref refs/workspace-wasm-modifiers)
        objects   (mf/deref refs/workspace-page-objects)]
    (dwcm/frame-pin-transform (get objects frame-id)
                              (get-in modifiers [frame-id :modifiers])
                              (get wasm-mods frame-id))))

(mf/defc comment-floating-bubble-wrapper*
  {::mf/private true}
  [{:keys [thread zoom is-open offset ring]}]
  (let [position-modifier (use-frame-position-modifier (:frame-id thread))]
    [:> cmt/comment-floating-bubble*
     {:thread thread
      :zoom zoom
      :position-modifier position-modifier
      :offset offset
      :ring ring
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

(mf/defc comment-floating-ghost-wrapper*
  {::mf/private true}
  [{:keys [thread-group zoom]}]
  (let [thread            (first thread-group)
        position-modifier (use-frame-position-modifier (:frame-id thread))]
    [:> cmt/comment-floating-ghost*
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

    ;; Any viewport change (pan/zoom) collapses an expanded cluster back.
    (mf/with-effect [vbox zoom]
      (st/emit! (dcm/collapse-comment-group)))

    [:div {:class (stl/css :comments-section)}
     [:div
      {:id "comments"
       :class (stl/css :workspace-comments-container)
       :style {:width (dm/str vport-w "px")
               :height (dm/str vport-h "px")}}
      [:div {:class (stl/css :threads)
             :style {:transform (dm/fmt "translate(%px, %px)" pos-x pos-y)}}

       (for [thread-group (dwcm/group-bubbles zoom threads)]
         (let [group?    (> (count thread-group) 1)
               thread    (first thread-group)
               expanded? (and group?
                              (= (:expanded local) (into #{} (map :id) thread-group)))]
           (cond
             expanded?
             [:* {:key (:seqn thread)}
              [:> comment-floating-ghost-wrapper* {:thread-group thread-group
                                                   :zoom zoom}]
              (for [[thread offset ring] (cmt/expanded-group-offsets zoom thread-group)]
                [:> comment-floating-bubble-wrapper* {:thread thread
                                                      :zoom zoom
                                                      :offset offset
                                                      :ring ring
                                                      :is-open false
                                                      :key (:seqn thread)}])]

             group?
             [:> comment-floating-group-wrapper* {:thread-group thread-group
                                                  :zoom zoom
                                                  :key (:seqn thread)}]

             :else
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
