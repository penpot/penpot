;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.comments :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private comments-icon-svg
  (deprecated-icon/icon-xref :comments (stl/css :comments-icon)))

(mf/defc comments-icon*
  {::mf/props :obj}
  [{:keys [profile on-show-comments]}]

  (let [threads-map (mf/deref refs/comment-threads)

        tgroups
        (->> (vals threads-map)
             (sort-by :modified-at)
             (reverse)
             (dcm/apply-filters {} profile)
             (dcm/group-threads-by-file-and-page))]

    [:div {:class (stl/css :dashboard-comments-section)}
     [:> icon-button* {:variant "ghost"
                       :tab-index "0"
                       :class (stl/css :comment-button)
                       :data-testid "open-comments"
                       :aria-label (tr "dashboard.notifications.view")
                       :on-click on-show-comments
                       :icon i/comments}
      (when (seq tgroups)
        [:div {:class (stl/css :unread)}])]]))

(mf/defc comments-section
  [{:keys [profile team show? on-hide-comments]}]
  (let [threads-map    (mf/deref refs/comment-threads)

        ;; FIXME: with-memo
        team-id        (:id team)
        tgroups        (->> (vals threads-map)
                            (sort-by :modified-at)
                            (reverse)
                            (dcm/apply-filters {} profile)
                            (dcm/group-threads-by-file-and-page))

        on-navigate
        (mf/use-callback
         (fn [thread]
           (st/emit! (-> (dwcm/navigate-to-comment-from-dashboard thread)
                         (with-meta {::ev/origin "dashboard"})))))

        on-read-all
        (mf/use-callback
         (mf/deps team-id)
         (fn []
           (st/emit! (dcm/mark-all-threads-as-read team-id))))]

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

    [:div {:class (stl/css :dashboard-comments-section)}
     [:& dropdown {:show show? :on-close on-hide-comments :dropdown-id "dashboard-comments"}
      [:div {:class (stl/css :dropdown :comments-section :comment-threads-section)}
       [:div {:class (stl/css :header)}
        [:h3 {:class (stl/css :header-title)} (tr "dashboard.notifications")]
        (when (seq tgroups)
          [:> icon-button* {:class (stl/css :mark-all-as-read-button :notifications-button)
                            :variant "action"
                            :tab-index (if show? "0" "-1")
                            :aria-label (tr "label.mark-all-as-read")
                            :on-click on-read-all
                            :icon i/tick}])

        [:> icon-button* {:class (stl/css :notifications-button)
                          :variant "action"
                          :tab-index (if show? "0" "-1")
                          :aria-label (tr "labels.close")
                          :on-click on-hide-comments
                          :icon i/close}]]

       (if (seq tgroups)
         [:div {:class (stl/css :thread-groups)}
          [:> cmt/comment-dashboard-thread-group*
           {:group (first tgroups)
            :on-thread-click on-navigate
            :show-file-name true}]
          (for [tgroup (rest tgroups)]
            [:> cmt/comment-dashboard-thread-group*
             {:group tgroup
              :on-thread-click on-navigate
              :show-file-name true
              :key (:page-id tgroup)}])]

         [:div {:class (stl/css :thread-groups-placeholder)}
          comments-icon-svg
          (tr "labels.no-comments-available")])]]]))
