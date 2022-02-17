;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.placeholder
  (:require
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc empty-placeholder
  [{:keys [dragging? default?] :as props}]
  (cond
    (true? dragging?)
    [:div.grid-row.no-wrap
     [:div.grid-item]]

    (true? default?)
    [:div.grid-empty-placeholder.drafts {:data-test "empty-placeholder"}
     [:div.text
      [:& i18n/tr-html {:label "dashboard.empty-placeholder-drafts"}]]]

    :else
    [:div.grid-empty-placeholder
     [:img.ph-files {:src "images/ph-file.svg"}]]))

(mf/defc loading-placeholder
  []
  [:div.grid-empty-placeholder
   [:div.icon i/loader]
   [:div.text (tr "dashboard.loading-files")]])

