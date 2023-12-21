;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.comments :as dcm]
   [app.main.data.events :as ev]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc comments-icon
  [{:keys [profile show? on-show-comments]}]

  (let [threads-map (mf/deref refs/comment-threads)

        tgroups
        (->> (vals threads-map)
             (sort-by :modified-at)
             (reverse)
             (dcm/apply-filters {} profile)
             (dcm/group-threads-by-file-and-page))

        handle-keydown
        (mf/use-callback
         (mf/deps on-show-comments)
         (fn [event]
           (when (kbd/enter? event)
             (on-show-comments event))))]

    [:div {:class (stl/css :dashboard-comments-section)}
     [:button
      {:tab-index "0"
       :on-click on-show-comments
       :on-key-down handle-keydown
       :data-test "open-comments"
       :class (stl/css-case :button true
                            :open show?
                            :unread (boolean (seq tgroups)))}
      i/chat]]))

(mf/defc comments-section
  [{:keys [profile team show? on-show-comments on-hide-comments]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)

        threads-map    (mf/deref refs/comment-threads)
        users          (mf/deref refs/current-team-comments-users)
        team-id        (:id team)

        tgroups        (->> (vals threads-map)
                            (sort-by :modified-at)
                            (reverse)
                            (dcm/apply-filters {} profile)
                            (dcm/group-threads-by-file-and-page))

        handle-keydown
        (mf/use-callback
         (mf/deps on-hide-comments)
         (fn [event]
           (when (kbd/enter? event)
             (on-hide-comments event))))

        on-navigate
        (mf/use-callback
         (fn [thread]
           (st/emit! (-> (dwcm/navigate thread)
                         (with-meta {::ev/origin "dashboard"})))))]

    (mf/use-effect
      (mf/deps team-id)
      (fn []
        (st/emit! (dcm/retrieve-unread-comment-threads team-id))))

    (mf/use-effect
     (mf/deps show?)
     (fn []
       (when show?
         (st/emit! (ptk/event ::ev/event {::ev/name "open-comment-notifications"
                                          ::ev/origin "dashboard"})))))

    (if new-css-system
      [:div {:class (stl/css :dashboard-comments-section)}
       [:& dropdown {:show show? :on-close on-hide-comments}
        [:div {:class (stl/css :dropdown :comments-section :comment-threads-section)}
         [:div {:class (stl/css :header)}
          [:h3 (tr "labels.comments")]
          [:button
           {:class (stl/css :close)
            :tab-index (if show? "0" "-1")
            :on-click on-hide-comments
            :on-key-down handle-keydown} i/close]]

         (if (seq tgroups)
           [:div {:class (stl/css :thread-groups)}
            [:& cmt/comment-thread-group
             {:group (first tgroups)
              :on-thread-click on-navigate
              :show-file-name true
              :users users}]
            (for [tgroup (rest tgroups)]
              [:& cmt/comment-thread-group
               {:group tgroup
                :on-thread-click on-navigate
                :show-file-name true
                :users users
                :key (:page-id tgroup)}])]

           [:div {:class (stl/css :thread-groups-placeholder)}
            i/chat
            (tr "labels.no-comments-available")])]]]

      ;; OLD
      [:div.dashboard-comments-section
       [:div.button
        {:tab-index "0"
         :on-click on-show-comments
         :on-key-down (fn [event]
                        (when (kbd/enter? event)
                          (on-show-comments event)))
         :data-test "open-comments"
         :class (dom/classnames :open show?
                                :unread (boolean (seq tgroups)))}
        i/chat]

       [:& dropdown {:show show? :on-close on-hide-comments}
        [:div.dropdown.comments-section.comment-threads-section.
         [:div.header
          [:h3 (tr "labels.comments")]
          [:span.close {:tab-index (if show? "0" "-1")
                        :on-click on-hide-comments
                        :on-key-down handle-keydown}
           i/close]]

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
            (tr "labels.no-comments-available")])]]])))
