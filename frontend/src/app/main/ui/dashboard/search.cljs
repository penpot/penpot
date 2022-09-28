;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.search
  (:require
   [app.common.math :as mth]
   [app.main.data.dashboard :as dd]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc search-page
  [{:keys [team search-term] :as props}]

  (mf/use-effect
   (mf/deps team)
   (fn []
     (when team
       (let [tname (if (:is-default team)
                     (tr "dashboard.your-penpot")
                     (:name team))]
         (dom/set-html-title (tr "title.dashboard.search" tname))))))

  (mf/use-effect
   (mf/deps search-term)
   (fn []
     (st/emit! (dd/search {:search-term search-term})
               (dd/clear-selected-files))))

  (let [result (mf/deref refs/dashboard-search-result)
        width            (mf/use-state nil)
        rowref           (mf/use-ref)
        itemsize       (if (>= @width 1030)
                         280
                         230)

        ratio          (if (some? @width) (/ @width itemsize) 0)
        nitems         (mth/floor ratio)
        limit          (min 10 nitems)
        limit          (max 1 limit)]
    (mf/use-effect
     (fn []
       (let [node (mf/ref-val rowref)
             mnt? (volatile! true)
             sub  (->> (wapi/observe-resize node)
                       (rx/observe-on :af)
                       (rx/subs (fn [entries]
                                  (let [row (first entries)
                                        row-rect (.-contentRect ^js row)
                                        row-width (.-width ^js row-rect)]
                                    (when @mnt?
                                      (reset! width row-width))))))]
         (fn []
           (vreset! mnt? false)
           (rx/dispose! sub)))))
    [:*
     [:header.dashboard-header
      [:div.dashboard-title
       [:h1 (tr "dashboard.title-search")]]]

     [:section.dashboard-container.search.no-bg {:ref rowref}
      (cond
        (empty? search-term)
        [:div.grid-empty-placeholder.search
         [:div.icon i/search]
         [:div.text (tr "dashboard.type-something")]]

        (nil? result)
        [:div.grid-empty-placeholder.search
         [:div.icon i/search]
         [:div.text (tr "dashboard.searching-for" search-term)]]

        (empty? result)
        [:div.grid-empty-placeholder.search
         [:div.icon i/search]
         [:div.text (tr "dashboard.no-matches-for" search-term)]]

        :else
        [:& grid {:files result
                  :hide-new? true
                  :limit limit}])]]))
