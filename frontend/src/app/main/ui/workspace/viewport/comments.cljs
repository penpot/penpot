;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.context :as ctx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc comments-layer
  [{:keys [vbox vport zoom file-id page-id drawing] :as props}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        pos-x                 (* (- (:x vbox)) zoom)
        pos-y                 (* (- (:y vbox)) zoom)

        profile               (mf/deref refs/profile)
        users                 (mf/deref refs/current-file-comments-users)
        local                 (mf/deref refs/comments-local)
        threads-position-ref  (l/derived (l/in [:workspace-data :pages-index page-id :options :comment-threads-position]) st/state)
        threads-position-map  (mf/deref threads-position-ref)
        threads-map           (mf/deref refs/threads-ref)

        update-thread-position (fn update-thread-position [thread]
                                 (if (contains? threads-position-map (:id thread))
                                   (-> thread
                                       (assoc :position (get-in threads-position-map [(:id thread) :position]))
                                       (assoc :frame-id (get-in threads-position-map [(:id thread) :frame-id])))
                                   thread))

        threads               (->> (vals threads-map)
                                   (filter #(= (:page-id %) page-id))
                                   (mapv update-thread-position)
                                   (dcm/apply-filters local profile))

        on-draft-cancel
        (mf/use-callback
         #(st/emit! :interrupt))

        on-draft-submit
        (mf/use-callback
         (fn [draft]
           (st/emit! (dcm/create-thread-on-workspace draft))))]

    (mf/use-effect
     (mf/deps file-id)
     (fn []
       (st/emit! (dwcm/initialize-comments file-id))
       (fn []
         (st/emit! ::dwcm/finalize))))
    (if new-css-system
      [:div {:class (stl/css :comments-section)}
       [:div
        {:class (stl/css :workspace-comments-container)
         :style {:width (str (:width vport) "px")
                 :height (str (:height vport) "px")}}
        [:div {:class (stl/css :threads)
               :style {:transform (str/format "translate(%spx, %spx)" pos-x pos-y)}}
         (for [item threads]
           [:& cmt/thread-bubble {:thread item
                                  :zoom zoom
                                  :open? (= (:id item) (:open local))
                                  :key (:seqn item)}])

         (when-let [id (:open local)]
           (when-let [thread (get threads-map id)]
             [:& cmt/thread-comments {:thread (update-thread-position thread)
                                      :users users
                                      :zoom zoom}]))

         (when-let [draft (:comment drawing)]
           [:& cmt/draft-thread {:draft draft
                                 :on-cancel on-draft-cancel
                                 :on-submit on-draft-submit
                                 :zoom zoom}])]]]

      ;; OLD
      [:div.comments-section
       [:div.workspace-comments-container
        {:style {:width (str (:width vport) "px")
                 :height (str (:height vport) "px")}}
        [:div.threads {:style {:transform (str/format "translate(%spx, %spx)" pos-x pos-y)}}
         (for [item threads]
           [:& cmt/thread-bubble {:thread item
                                  :zoom zoom
                                  :open? (= (:id item) (:open local))
                                  :key (:seqn item)}])

         (when-let [id (:open local)]
           (when-let [thread (get threads-map id)]
             [:& cmt/thread-comments {:thread (update-thread-position thread)
                                      :users users
                                      :zoom zoom}]))

         (when-let [draft (:comment drawing)]
           [:& cmt/draft-thread {:draft draft
                                 :on-cancel on-draft-cancel
                                 :on-submit on-draft-submit
                                 :zoom zoom}])]]])

    ))
