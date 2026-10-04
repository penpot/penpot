;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.branches-popover
  "Dashboard file-card popover that lists a file's branches and lets you
  open one or create a new branch without entering the file."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private dashboard-branches
  (l/derived :dashboard-branches st/state))

(mf/defc branches-popover*
  [{:keys [file n]}]
  (let [show?    (mf/use-state false)
        branches (mf/deref dashboard-branches)

        on-toggle
        (mf/use-fn
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (when-not (deref show?)
             ;; clear the previous file's list before fetching so the
             ;; popover never flashes another card's branches
             (st/emit! (dwb/load-file-branches (:id file))))
           (swap! show? not)))

        on-close (mf/use-fn #(reset! show? false))

        on-create
        (mf/use-fn
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (reset! show? false)
           (modal/show! :create-branch {:file-name (:name file) :file-id (:id file)})))

        on-open-branch
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [bid (-> event dom/get-current-target (dom/get-data "branch") uuid/parse)]
             (st/emit! (dwb/open-branch bid)))))]

    [:div {:class (stl/css :branches-popover-wrap)
           :on-click (fn [e] (dom/stop-propagation e))}
     [:div {:class (stl/css :branches-badge)
            :role "button"
            :aria-label (tr "dashboard.branches-badge" (dm/str n))
            :on-click on-toggle}
      [:> i/icon* {:icon-id i/git-branch :size "s"}]
      [:span (dm/str n)]]

     [:& dropdown {:show (deref show?) :on-close on-close}
      [:div {:class (stl/css :branches-popover)}
       [:ul {:class (stl/css :branches-popover-list)}
        (for [b branches]
          [:li {:key (dm/str (:id b))
                :class (stl/css :branches-popover-item)
                :role "button"
                :data-branch (dm/str (:branch-file-id b))
                :on-click on-open-branch}
           [:> i/icon* {:icon-id i/git-branch :size "s"}]
           [:span {:class (stl/css :branches-popover-name)} (:name b)]
           [:span {:class (stl/css :branches-popover-counts)}
            (dm/str "↑" (:ahead b) " ↓" (:behind b))]])]
       [:> button* {:variant "primary" :icon i/add :on-click on-create}
        (tr "workspace.branches.new")]]]]))
