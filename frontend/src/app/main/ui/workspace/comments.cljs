;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.comments
  (:require-macros [app.main.style :as stl])
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
  {::mf/props :obj}
  [{:keys [from-viewer]}]
  (let [{cmode :mode cshow :show} (mf/deref refs/comments-local)
        update-mode
        (mf/use-fn
         (fn [event]
           (let [mode (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (keyword))]
             (st/emit! (dcm/update-filters {:mode mode})))))

        update-show
        (mf/use-fn
         (mf/deps cshow)
         (fn []
           (let [mode (if (= :pending cshow) :all :pending)]
             (st/emit! (dcm/update-filters {:show mode})))))]

    [:ul {:class (stl/css-case :comment-mode-dropdown true
                               :viewer-dropdown from-viewer)}
     [:li {:class (stl/css-case :dropdown-item true
                                :selected (or (= :all cmode) (nil? cmode)))
           :data-value "all"
           :on-click update-mode}

      [:span {:class (stl/css :label)} (tr "labels.show-all-comments")]
      [:span {:class (stl/css :icon)} i/tick]]
     [:li {:class  (stl/css-case :dropdown-item true
                                 :selected (= :yours cmode))
           :data-value "yours"
           :on-click update-mode}
      [:span {:class (stl/css :label)}  (tr "labels.show-your-comments")]
      [:span {:class (stl/css :icon)} i/tick]]
     [:li {:class (stl/css :separator)}]
     [:li {:class (stl/css-case :dropdown-item true
                                :selected (= :pending cshow))
           :on-click update-show}
      [:span {:class (stl/css :label)}  (tr "labels.hide-resolved-comments")]
      [:span {:class (stl/css :icon)} i/tick]]]))

(mf/defc comments-sidebar
  {::mf/props :obj}
  [{:keys [users threads page-id from-viewer]}]
  (let [threads-map (mf/deref refs/threads-ref)
        profile     (mf/deref refs/profile)
        users-refs  (mf/deref refs/current-file-comments-users)
        users       (or users users-refs)
        local       (mf/deref refs/comments-local)

        state*      (mf/use-state false)
        options?    (deref state*)

        threads     (if (nil? threads)
                      (->> (vals threads-map)
                           (sort-by :modified-at)
                           (reverse)
                           (dcm/apply-filters local profile))
                      threads)

        close-section
        (mf/use-fn
         (mf/deps from-viewer)
         (fn []
           (if from-viewer
             (st/emit! (dcm/update-options {:show-sidebar? false}))
             (st/emit! :interrupt (dw/deselect-all true)))))

        tgroups     (->> threads
                         (dcm/group-threads-by-page))

        page-id     (or page-id (mf/use-ctx ctx/current-page-id))

        toggle-mode-selector
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! state* not)))

        on-thread-click
        (mf/use-fn
         (mf/deps page-id)
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
    [:div  {:class (stl/css-case :comments-section true
                                 :from-viewer  from-viewer)}
     [:div {:class (stl/css-case :comments-section-title true
                                 :viewer-title from-viewer)}
      [:span (tr "labels.comments")]
      [:button {:class (stl/css :close-button)
                :on-click close-section}
       i/close]]

     [:button {:class (stl/css :mode-dropdown-wrapper)
               :on-click toggle-mode-selector}

      [:span {:class (stl/css :mode-label)} (case (:mode local)
                                              (nil :all) (tr "labels.show-all-comments")
                                              :yours     (tr "labels.show-your-comments"))]
      [:div {:class (stl/css :arrow-icon)} i/arrow]]

     [:& dropdown {:show options?
                   :on-close #(reset! state* false)}
      [:& sidebar-options {:local local :from-viewer from-viewer}]]

     [:div {:class (stl/css :comments-section-content)}

      (if (seq tgroups)
        [:div {:class (stl/css :thread-groups)}
         [:& cmt/comment-thread-group
          {:group (first tgroups)
           :on-thread-click on-thread-click
           :users users}]
         (for [tgroup (rest tgroups)]
           [:& cmt/comment-thread-group
            {:group tgroup
             :on-thread-click on-thread-click
             :users users
             :key (:page-id tgroup)}])]

        [:div {:class (stl/css :thread-group-placeholder)}
         [:span {:class (stl/css :placeholder-icon)} i/comments]
         [:span {:class (stl/css :placeholder-label)}
          (tr "labels.no-comments-available")]])]]))
