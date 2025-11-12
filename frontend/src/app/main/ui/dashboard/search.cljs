;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.search
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:search-result
  (l/derived :search-result st/state))

(def ^:private ref:selected
  (l/derived (fn [state]
               ;; we need to this because :dashboard-search-result is a list
               ;; of maps and we need a map of maps (using :id as key).
               (let [files (d/index-by :id (:search-result state))]
                 (->> (get state :selected-files)
                      (refs/extract-selected-files files))))
             st/state))

(mf/defc search-page*
  {::mf/props :obj}
  [{:keys [team search-term]}]
  (let [search-term (d/nilv search-term "")

        result      (mf/deref ref:search-result)
        selected    (mf/deref ref:selected)

        [rowref limit]
        (hooks/use-dynamic-grid-item-width)]

    (mf/with-effect [team]
      (when team
        (let [tname (if (:is-default team)
                      (tr "dashboard.your-penpot")
                      (:name team))]
          (dom/set-html-title (tr "title.dashboard.search" tname)))))

    (mf/with-effect [search-term]
      (st/emit! (dd/search {:search-term search-term})
                (dd/clear-selected-files)))

    [:*
     [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
      [:div#dashboard-search-title {:class (stl/css :dashboard-title)}
       [:h1 (tr "dashboard.title-search")]]]

     [:section {:class (stl/css :dashboard-container :search :no-bg)
                :ref rowref}
      (cond
        (empty? search-term)
        [:div {:class (stl/css :grid-empty-placeholder :search)}
         [:div {:class (stl/css :icon)} deprecated-icon/search]
         [:div {:class (stl/css :text)} (tr "dashboard.type-something")]]

        (nil? result)
        [:div {:class (stl/css :grid-empty-placeholder :search)}
         [:div {:class (stl/css :icon)} deprecated-icon/search]
         [:div {:class (stl/css :text)} (tr "dashboard.searching-for" search-term)]]

        (empty? result)
        [:div {:class (stl/css :grid-empty-placeholder :search)}
         [:div {:class (stl/css :icon)} deprecated-icon/search]
         [:div {:class (stl/css :text)} (tr "dashboard.no-matches-for" search-term)]]

        :else
        [:> grid* {:files result
                   :selected-files selected
                   :origin :search
                   :limit limit}])]]))
