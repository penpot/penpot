;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.comments
  (:require
   [app.config :as cfg]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.comments :as dcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.comments :as cmt]
   [app.util.time :as dt]
   [app.util.timers :as tm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def threads-ref
  (l/derived :comment-threads st/state))

(mf/defc comments-layer
  [{:keys [vbox vport zoom file-id page-id drawing] :as props}]
  (let [pos-x       (* (- (:x vbox)) zoom)
        pos-y       (* (- (:y vbox)) zoom)

        profile     (mf/deref refs/profile)
        users       (mf/deref refs/users)
        local       (mf/deref refs/comments-local)
        threads-map (mf/deref threads-ref)

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc sidebar-options
  [{:keys [local] :as props}]
  (let [{cmode :mode cshow :show} (mf/deref refs/comments-local)
        locale (mf/deref i18n/locale)

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
      [:span.label (t locale "labels.show-all-comments")]]

     [:li {:class (dom/classnames :selected (= :yours cmode))
           :on-click #(update-mode :yours)}
      [:span.icon i/tick]
      [:span.label (t locale "labels.show-your-comments")]]

     [:hr]

     [:li {:class (dom/classnames :selected (= :pending cshow))
           :on-click #(update-show (if (= :pending cshow) :all :pending))}
      [:span.icon i/tick]
      [:span.label (t locale "labels.hide-resolved-comments")]]]))

(mf/defc comments-sidebar
  []
  (let [threads-map (mf/deref threads-ref)
        profile     (mf/deref refs/profile)
        users       (mf/deref refs/users)
        local       (mf/deref refs/comments-local)
        options?    (mf/use-state false)

        tgroups     (->> (vals threads-map)
                         (sort-by :modified-at)
                         (reverse)
                         (dcm/apply-filters local profile)
                         (dcm/group-threads-by-page))

        page-id     (mf/use-ctx ctx/current-page-id)

        on-thread-click
        (mf/use-callback
         (fn [thread]
           (when (not= page-id (:page-id thread))
             (st/emit! (dw/go-to-page (:page-id thread))))
           (tm/schedule
            (st/emitf (dwcm/center-to-comment-thread thread)
                      (dcm/open-thread thread)))))]

    [:div.comments-section.comment-threads-section
     [:div.workspace-comment-threads-sidebar-header
      [:div.label "Comments"]
      [:div.options {:on-click #(reset! options? true)}
       [:div.label (case (:mode local)
                     (nil :all) (tr "labels.all")
                     :yours     (tr "labels.only-yours"))]
       [:div.icon i/arrow-down]]

      [:& dropdown {:show @options?
                    :on-close #(reset! options? false)}
       [:& sidebar-options {:local local}]]]

     (when (seq tgroups)
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
             :key (:page-id tgroup)}]])])]))


