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
           (st/emit! (dcm/create-thread draft)
                     #_(dcm/close-thread))))
        ]


    (mf/use-effect
     (mf/deps file-id)
     (fn []
       (st/emit! (dwcm/initialize-comments file-id))
       (fn []
         (st/emit! ::dwcm/finalize))))

    [:div.workspace-comments
     [:div.comments-layer
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
                                    :zoom zoom}]))

       (when-let [draft (:comment drawing)]
         [:& cmt/draft-thread {:draft draft
                               :on-cancel on-draft-cancel
                               :on-submit on-draft-submit
                               :zoom zoom}])]]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc sidebar-group-item
  [{:keys [item] :as props}]
  (let [profile (get @refs/workspace-users (:owner-id item))
        page-id (mf/use-ctx ctx/current-page-id)
        file-id (mf/use-ctx ctx/current-file-id)

        on-click
        (mf/use-callback
         (mf/deps item page-id)
         (fn []
           (when (not= page-id (:page-id item))
             (st/emit! (dw/go-to-page (:page-id item))))
           (tm/schedule
            (st/emitf (dwcm/center-to-comment-thread item)
                      (dcm/open-thread item)))))]

    [:div.comment {:on-click on-click}
     [:div.author
      [:div.thread-bubble
       {:class (dom/classnames
                :resolved (:is-resolved item)
                :unread (pos? (:count-unread-comments item)))}
       (:seqn item)]
      [:div.avatar
       [:img {:src (cfg/resolve-media-path (:photo profile))}]]
      [:div.name
       [:div.fullname (:fullname profile) ", "]
       [:div.timeago (dt/timeago (:modified-at item))]]]
     [:div.content
      [:span.text (:content item)]]
     [:div.content.replies
      (let [unread (:count-unread-comments item ::none)
            total  (:count-comments item 1)]
        [:*
         (when (> total 1)
           (if (= total 2)
             [:span.total-replies "1 reply"]
             [:span.total-replies (str (dec total) " replies")]))

         (when (and (> total 1) (> unread 0))
           (if (= unread 1)
             [:span.new-replies "1 new reply"]
             [:span.new-replies (str unread " new replies")]))])]]))

(defn page-name-ref
  [id]
  (l/derived (l/in [:workspace-data :pages-index id :name]) st/state))

(mf/defc sidebar-item
  [{:keys [group]}]
  (let [page-name-ref (mf/use-memo (mf/deps (:page-id group)) #(page-name-ref (:page-id group)))
        page-name     (mf/deref page-name-ref)]
    [:div.page-section
     [:div.section-title
      [:span.icon i/file-html]
      [:span.label page-name]]
     [:div.comments-container
      (for [item (:items group)]
        [:& sidebar-group-item {:item item :key (:id item)}])]]))

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

    [:ul.dropdown.with-check.sidebar-options-dropdown
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
        local       (mf/deref refs/comments-local)
        options?    (mf/use-state false)

        tgroups     (->> (vals threads-map)
                         (sort-by :modified-at)
                         (reverse)
                         (dcm/apply-filters local profile)
                         (dcm/group-threads-by-page))]

    [:div.workspace-comments.workspace-comments-sidebar
     [:div.sidebar-title
      [:div.label "Comments"]
      [:div.options {:on-click #(reset! options? true)}
       [:div.label (case (:filter local)
                     (nil :all) "All"
                     :yours     "Only yours")]
       [:div.icon i/arrow-down]]]

     [:& dropdown {:show @options?
                   :on-close #(reset! options? false)}
      [:& sidebar-options {:local local}]]

     (when (seq tgroups)
       [:div.threads
        [:& sidebar-item {:group (first tgroups)}]
        (for [tgroup (rest tgroups)]
          [:*
           [:hr]
           [:& sidebar-item {:group tgroup
                             :key (:page-id tgroup)}]])])]))


