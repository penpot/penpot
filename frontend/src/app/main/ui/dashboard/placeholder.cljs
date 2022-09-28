;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.placeholder
  (:require
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc empty-placeholder
  [{:keys [dragging? on-create-clicked project limit origin] :as props}]
  (cond
    (true? dragging?)
    [:div.grid-row.no-wrap
     {:style {:grid-template-columns (str "repeat(" limit ", 1fr)")}}
     [:div.grid-item]]
    (= :libraries origin) 
    [:div.grid-empty-placeholder.libs {:data-test "empty-placeholder"}
     [:div.text
      [:& i18n/tr-html {:label "dashboard.empty-placeholder-drafts"}]]]
    :else
    [:div.grid-empty-placeholder
     {:style {:grid-template-columns (str "repeat(" limit ", 1fr)")}}
     [:button.create-new {:on-click (partial on-create-clicked project "dashboard:empty-folder-placeholder")}
      (tr "dashboard.new-file")]]))

(mf/defc loading-placeholder
  []
  [:div.grid-empty-placeholder
   [:div.icon i/loader]
   [:div.text (tr "dashboard.loading-files")]])

