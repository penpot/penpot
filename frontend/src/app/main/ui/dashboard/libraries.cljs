;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.libraries
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.data.dashboard :as dd]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc libraries-page
  [{:keys [team] :as props}]
  (let [files-map       (mf/deref refs/dashboard-shared-files)
        projects        (mf/deref refs/dashboard-projects)

        default-project (->> projects vals (d/seek :is-default))

        files           (mf/with-memo [files-map]
                          (if (nil? files-map)
                            nil
                            (->> (vals files-map)
                                 (sort-by :modified-at)
                                 (reverse))))

        components-v2   (features/use-feature "components/v2")

        width           (mf/use-state nil)
        rowref          (mf/use-ref)

        itemsize        (if components-v2
                          350
                          (if (>= @width 1030) 280 230))
        ratio           (if (some? @width) (/ @width itemsize) 0)
        nitems          (mth/floor ratio)
        limit           (min 10 nitems)
        limit           (max 1 limit)]

    (mf/with-effect [team]
      (when team
        (let [tname (if (:is-default team)
                      (tr "dashboard.your-penpot")
                      (:name team))]
          (dom/set-html-title (tr "title.dashboard.shared-libraries" tname)))))

    (mf/with-effect []
      (st/emit! (dd/fetch-shared-files)
                (dd/clear-selected-files)))

    (mf/with-effect []
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
          (rx/dispose! sub))))

    [:*
     [:header.dashboard-header {:ref rowref}
      [:div.dashboard-title#dashboard-libraries-title
       [:h1 (tr "dashboard.libraries-title")]]]
     [:section.dashboard-container.no-bg.dashboard-shared
      [:& grid {:files files
                :project default-project
                :origin :libraries
                :limit limit
                :library-view? components-v2}]]]))

