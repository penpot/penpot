;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.comments
  (:require
   [app.main.data.comments :as dcm]
   [app.main.data.events :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc sidebar-options
  []
  (let [{cmode :mode cshow :show} (mf/deref refs/comments-local)
        update-mode
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-filters {:mode mode}))))

        update-show
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-filters {:show mode}))))]

    [:ul.dropdown.with-check
     [:li {:class (dom/classnames :selected (or (= :all cmode) (nil? cmode)))
           :on-click #(update-mode :all)}
      [:span.icon i/tick]
      [:span.label (tr "labels.show-all-comments")]]

     [:li {:class (dom/classnames :selected (= :yours cmode))
           :on-click #(update-mode :yours)}
      [:span.icon i/tick]
      [:span.label (tr "labels.show-your-comments")]]

     [:hr]

     [:li {:class (dom/classnames :selected (= :pending cshow))
           :on-click #(update-show (if (= :pending cshow) :all :pending))}
      [:span.icon i/tick]
      [:span.label (tr "labels.hide-resolved-comments")]]]))

(mf/defc comments-sidebar
  [{:keys [users threads page-id]}]
  (let [threads-map (mf/deref refs/threads-ref)
        profile     (mf/deref refs/profile)
        users-refs  (mf/deref refs/current-file-comments-users)
        users       (or users users-refs)
        local       (mf/deref refs/comments-local)
        options?    (mf/use-state false)
        threads     (if (nil? threads)
                      (->> (vals threads-map)
                           (sort-by :modified-at)
                           (reverse)
                           (dcm/apply-filters local profile))
                      threads)
        tgroups (->> threads
                     (dcm/group-threads-by-page))

        page-id     (or page-id (mf/use-ctx ctx/current-page-id))

        on-thread-click
        (mf/use-callback
         (fn [thread]
           (when (not= page-id (:page-id thread))
             (st/emit! (dw/go-to-page (:page-id thread))))
           (tm/schedule
            (fn []
              (st/emit! (when (not= page-id (:page-id thread))
                          (dw/select-for-drawing :comments))
                        (dwcm/center-to-comment-thread thread)
                        (-> (dcm/open-thread thread)
                            (with-meta {::ev/origin "workspace"})))))))]

    [:div.comments-section.comment-threads-section
     [:div.workspace-comment-threads-sidebar-header
      [:div.label (tr "labels.comments")]
      [:div.options {:on-click #(reset! options? true)}
       [:div.label (case (:mode local)
                     (nil :all) (tr "labels.all")
                     :yours     (tr "labels.only-yours"))]
       [:div.icon i/arrow-down]]

      [:& dropdown {:show @options?
                    :on-close #(reset! options? false)}
       [:& sidebar-options {:local local}]]]

     (if (seq tgroups)
       [:div.thread-groups
        [:& cmt/comment-thread-group
         {:group (first tgroups)
          :on-thread-click on-thread-click
          :users users}]
        (for [tgroup (rest tgroups)]
          [:*
           [:hr]
           [:& cmt/comment-thread-group
            {:group tgroup
             :on-thread-click on-thread-click
             :users users
             :key (:page-id tgroup)}]])]

       [:div.thread-groups-placeholder
        i/chat
        (tr "labels.no-comments-available")])]))
