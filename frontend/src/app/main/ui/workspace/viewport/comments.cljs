;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.comments
  (:require
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc comments-layer
  [{:keys [vbox vport zoom file-id page-id drawing] :as props}]
  (let [pos-x       (* (- (:x vbox)) zoom)
        pos-y       (* (- (:y vbox)) zoom)

        profile     (mf/deref refs/profile)
        users       (mf/deref refs/users)
        local       (mf/deref refs/comments-local)
        threads-map (mf/deref refs/threads-ref)

        threads     (->> (vals threads-map)
                         (filter #(= (:page-id %) page-id))
                         (dcm/apply-filters local profile))

        on-bubble-click
        (fn [{:keys [id] :as thread}]
          (if (= (:open local) id)
            (st/emit! (dcm/close-thread))
            (st/emit! (dcm/open-thread thread))))

        on-draft-cancel
        (mf/use-callback
         (st/emitf :interrupt))

        on-draft-submit
        (mf/use-callback
         (fn [draft]
           (st/emit! (dcm/create-thread draft))))]

    (mf/use-effect
     (mf/deps file-id)
     (fn []
       (st/emit! (dwcm/initialize-comments file-id))
       (fn []
         (st/emit! ::dwcm/finalize))))

    [:div.comments-section
     [:div.workspace-comments-container
      {:style {:width (str (:width vport) "px")
               :height (str (:height vport) "px")}}
      [:div.threads {:style {:transform (str/format "translate(%spx, %spx)" pos-x pos-y)}}
       (for [item threads]
         [:& cmt/thread-bubble {:thread item
                                :zoom zoom
                                :on-click on-bubble-click
                                :open? (= (:id item) (:open local))
                                :key (:seqn item)}])

       (when-let [id (:open local)]
         (when-let [thread (get threads-map id)]
           [:& cmt/thread-comments {:thread thread
                                    :users users
                                    :zoom zoom}]))

       (when-let [draft (:comment drawing)]
         [:& cmt/draft-thread {:draft draft
                               :on-cancel on-draft-cancel
                               :on-submit on-draft-submit
                               :zoom zoom}])]]]))


