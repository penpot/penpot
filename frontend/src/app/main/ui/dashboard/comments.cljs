;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.comments
  (:require
   [okulary.core :as l]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.auth :as da]
   [app.main.data.dashboard :as dd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.comments :as dcm]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.comments :as cmt]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.timers :as tm]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc comments-section
  [{:keys [profile team]}]
  (mf/use-effect
   (mf/deps team)
   (st/emitf (dcm/retrieve-unread-comment-threads (:id team))))

  (let [show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-fn #(reset! show-dropdown? true))
        hide-dropdown  (mf/use-fn #(reset! show-dropdown? false))
        threads-map    (mf/deref refs/comment-threads)
        users          (mf/deref refs/users)

        tgroups        (->> (vals threads-map)
                            (sort-by :modified-at)
                            (reverse)
                            (dcm/apply-filters {} profile)
                            (dcm/group-threads-by-file-and-page))

        on-navigate
        (mf/use-callback
         (fn [thread]
           (st/emit! (dwcm/navigate thread))))]

    [:div.dashboard-comments-section
     [:div.button
      {:on-click show-dropdown
       :class (dom/classnames :open @show-dropdown?
                              :unread (boolean (seq tgroups)))}
      i/chat]

     [:& dropdown {:show @show-dropdown? :on-close hide-dropdown}
      [:div.dropdown.comments-section.comment-threads-section.
       [:div.header
        [:h3 (tr "labels.comments")]
        [:span.close {:on-click hide-dropdown} i/close]]

       [:hr]

       (if (seq tgroups)
         [:div.thread-groups
          [:& cmt/comment-thread-group
           {:group (first tgroups)
            :on-thread-click on-navigate
            :show-file-name true
            :users users}]
          (for [tgroup (rest tgroups)]
            [:*
             [:hr]

             [:& cmt/comment-thread-group
              {:group tgroup
               :on-thread-click on-navigate
               :show-file-name true
               :users users
               :key (:page-id tgroup)}]])]

         [:div.thread-groups-placeholder
          i/chat
          (tr "labels.no-comments-available")])]]]))
