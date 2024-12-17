;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- update-position
  [positions {:keys [id] :as thread}]
  (if (contains? positions id)
    (-> thread
        (assoc :position (dm/get-in positions [id :position]))
        (assoc :frame-id (dm/get-in positions [id :frame-id])))
    thread))

(mf/defc comments-layer
  {::mf/props :obj}
  [{:keys [vbox vport zoom file-id page-id drawing] :as props}]
  (let [vbox-x   (dm/get-prop vbox :x)
        vbox-y   (dm/get-prop vbox :y)
        vport-w  (dm/get-prop vport :width)
        vport-h  (dm/get-prop vport :height)

        pos-x    (* (- vbox-x) zoom)
        pos-y    (* (- vbox-y) zoom)

        profile  (mf/deref refs/profile)
        profiles (mf/deref refs/profiles)
        local    (mf/deref refs/comments-local)

        positions-ref
        (mf/with-memo [page-id]
          ;; FIXME: use lookup helpers here
          (-> (l/in [:workspace-data :pages-index page-id :comment-thread-positions])
              (l/derived st/state)))

        positions   (mf/deref positions-ref)
        threads-map (mf/deref refs/threads-ref)

        threads
        (mf/with-memo [threads-map positions local profile]
          (->> (vals threads-map)
               (filter #(= (:page-id %) page-id))
               (mapv (partial update-position positions))
               (dcm/apply-filters local profile)))

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
      {:class (stl/css :workspace-comments-container)
       :style {:width (dm/str vport-w "px")
               :height (dm/str vport-h "px")}}
      [:div {:class (stl/css :threads)
             :style {:transform (dm/fmt "translate(%px, %px)" pos-x pos-y)}}
       (for [item threads]
         [:> cmt/comment-floating-bubble* {:thread item
                                           :profiles profiles
                                           :zoom zoom
                                           :is-open (= (:id item) (:open local))
                                           :key (:seqn item)}])

       (when-let [id (:open local)]
         (when-let [thread (get threads-map id)]
           (when (seq (dcm/apply-filters local profile [thread]))
             [:> cmt/comment-floating-thread* {:thread (update-position positions thread)
                                               :profiles profiles
                                               :viewport {:offset-x pos-x :offset-y pos-y :width (:width vport) :height (:height vport)}
                                               :zoom zoom}])))

       (when-let [draft (:comment drawing)]
         [:> cmt/comment-floating-thread-draft* {:draft draft
                                                 :on-cancel on-draft-cancel
                                                 :on-submit on-draft-submit
                                                 :zoom zoom}])]]]))

