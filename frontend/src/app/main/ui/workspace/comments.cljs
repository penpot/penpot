;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.comments :as dcmt]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.review :as dwrv]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.panel-title :refer [panel-title*]]
   [app.main.ui.workspace.review.panel :refer [comments-tab* tasks-tab*]]
   [app.main.ui.workspace.review.tasks :as rvt]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
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
             (st/emit! (dcmt/update-filters {:mode mode})))))

        update-show
        (mf/use-fn
         (mf/deps cshow)
         (fn []
           (let [mode (if (= :pending cshow) :all :pending)]
             (st/emit! (dcmt/update-filters {:show mode})))))]

    [:ul {:class (stl/css-case :comment-mode-dropdown true
                               :viewer-dropdown from-viewer)}
     [:li {:class (stl/css-case :dropdown-item true
                                :selected (or (= :all cmode) (nil? cmode)))
           :data-value "all"
           :on-click update-mode}

      [:span {:class (stl/css :label)} (tr "labels.show-all-comments")]
      [:span {:class (stl/css :icon)}
       [:> icon* {:icon-id i/tick :size "s" :class (stl/css :tick-icon)}]]]
     [:li {:class  (stl/css-case :dropdown-item true
                                 :selected (= :yours cmode))
           :data-value "yours"
           :on-click update-mode}
      [:span {:class (stl/css :label)}  (tr "labels.show-your-comments")]
      [:span {:class (stl/css :icon)}
       [:> icon* {:icon-id i/tick :size "s" :class (stl/css :tick-icon)}]]]
     [:li {:class (stl/css-case :dropdown-item true
                                :selected (= :mentions cmode))
           :data-value "mentions"
           :on-click update-mode}
      [:span {:class (stl/css :label)} (tr "labels.show-mentions")]
      [:span {:class (stl/css :icon)}
       [:> icon* {:icon-id i/tick :size "s" :class (stl/css :tick-icon)}]]]
     [:li {:class (stl/css :separator)}]
     [:li {:class (stl/css-case :dropdown-item true
                                :selected (= :pending cshow))
           :on-click update-show}
      [:span {:class (stl/css :label)}  (tr "labels.hide-resolved-comments")]
      [:span {:class (stl/css :icon)}
       [:> icon* {:icon-id i/tick :size "s" :class (stl/css :tick-icon)}]]]]))

(declare comments-tasks-tabs* comments-list*)

(mf/defc comments-sidebar*
  [{:keys [profiles threads page-id from-viewer]}]
  (let [threads-map (mf/deref refs/threads)
        profile     (mf/deref refs/profile)
        profiles'   (mf/deref refs/profiles)
        profiles    (or profiles profiles')
        local       (mf/deref refs/comments-local)

        state*      (mf/use-state false)
        options?    (deref state*)

        threads     (if (nil? threads)
                      (->> (vals threads-map)
                           (sort-by :modified-at)
                           (reverse)
                           (dcmt/apply-filters local profile))
                      threads)

        close-section
        (mf/use-fn
         (mf/deps from-viewer)
         (fn []
           (if from-viewer
             (st/emit! (dcmt/update-options {:show-sidebar? false}))
             (st/emit! (dwdc/clear-drawing)
                       (dw/clear-edition-mode)
                       (dw/deselect-all true)))))

        tgroups     (->> threads
                         (dcmt/group-threads-by-page))

        page-id     (or page-id (mf/use-ctx ctx/current-page-id))

        toggle-mode-selector
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! state* not)))

        on-thread-click
        (mf/use-fn
         (mf/deps page-id from-viewer)
         (fn [thread]
           (if from-viewer
             (st/emit! (with-meta (dcmt/open-thread thread) {::ev/origin "viewer"}))
             (st/emit! (dwcm/navigate-to-comment thread)))))]

    (if (and (not from-viewer) (dwtk/enabled?))
      [:> comments-tasks-tabs* {:threads (vals threads-map)
                                :on-close close-section}]

      [:div {:class (stl/css-case :comments-section true
                                  :from-viewer from-viewer)}

       [:> panel-title* {:class (stl/css :comments-title)
                         :text (tr "labels.comments")
                         :on-close close-section}]
       [:> comments-list* {:local local
                           :options? options?
                           :state* state*
                           :from-viewer from-viewer
                           :toggle-mode-selector toggle-mode-selector
                           :tgroups tgroups
                           :on-thread-click on-thread-click
                           :profiles profiles}]])))

(mf/defc comments-tasks-tabs*
  "The comments panel of a file with tasks: a tab for its comments and one
  for its tasks, beside the button that closes the panel."
  {::mf/private true}
  [{:keys [threads on-close]}]
  (let [local   (mf/deref refs/review-local)
        tasks*  (mf/deref refs/tasks)
        tab     (get local :panel-tab :comments)
        tasks   (mf/with-memo [tasks*]
                  (vec (rvt/visible-tasks tasks*)))
        tabs    (mf/with-memo [threads tasks]
                  [{:id "comments"
                    :label (tr "workspace.review.tab.comments" (count threads))}
                   {:id "tasks"
                    :label (tr "workspace.review.tab.tasks" (count tasks))}])
        on-tab  (mf/use-fn #(st/emit! (dwrv/set-panel-tab (keyword %))))
        button  (mf/with-memo [on-close]
                  (mf/html
                   [:> icon-button* {:variant "ghost"
                                     :aria-label (tr "labels.close")
                                     :on-click on-close
                                     :icon i/close}]))]

    (mf/with-effect []
      (st/emit! (dwtk/fetch-tasks)))

    [:div {:class (stl/css :comments-panel)}
     [:> tab-switcher* {:tabs tabs
                        :selected (name tab)
                        :on-change on-tab
                        :scrollable-panel true
                        :action-button-position "end"
                        :action-button button
                        :class (stl/css :comments-tabs)}
      [:div {:class (stl/css :comments-tab-content)}
       (if (= tab :tasks)
         [:> tasks-tab* {:tasks tasks :grouped true :show-review true}]
         [:> comments-tab* {:threads threads :show-page true :show-review true}])]]]))

(mf/defc comments-list*
  "The comment threads of the file, grouped by page, with the selector of
  which ones to show."
  {::mf/private true}
  [{:keys [local options? state* from-viewer toggle-mode-selector tgroups on-thread-click profiles]}]
  [:*
   [:button {:class (stl/css :mode-dropdown-wrapper)
             :type "button"
             :aria-haspopup "menu"
             :aria-expanded options?
             :on-click toggle-mode-selector}

    [:span {:class (stl/css :mode-label)}
     (case (:mode local)
       (nil :all) (tr "labels.show-all-comments")
       :yours     (tr "labels.show-your-comments")
       :mentions     (tr "labels.show-mentions"))]
    [:div {:class (stl/css :arrow-icon)}
     [:> icon* {:icon-id i/arrow-down :size "s" :class (stl/css :arrow-svg)}]]]

   [:& dropdown {:show options?
                 :on-close #(reset! state* false)}
    [:& sidebar-options {:local local :from-viewer from-viewer}]]

   [:div {:class (stl/css :comments-section-content)}

    (if (seq tgroups)
      [:div {:class (stl/css :thread-groups)}
       [:> cmt/comment-sidebar-thread-group*
        {:group (first tgroups)
         :on-thread-click on-thread-click
         :profiles profiles}]
       (for [tgroup (rest tgroups)]
         [:> cmt/comment-sidebar-thread-group*
          {:group tgroup
           :on-thread-click on-thread-click
           :profiles profiles
           :key (:page-id tgroup)}])]

      [:div {:class (stl/css :thread-group-placeholder)}
       [:> empty-state* {:icon i/comments
                         :text (tr "labels.no-comments-available")}]])]])
