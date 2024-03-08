;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.placeholder
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc empty-placeholder
  [{:keys [dragging? limit origin create-fn] :as props}]
  (let [on-click
        (mf/use-fn
         (mf/deps create-fn)
         (fn [_]
           (create-fn "dashboard:empty-folder-placeholder")))]
    (cond
      (true? dragging?)
      [:ul
       {:class (stl/css :grid-row :no-wrap)
        :style {:grid-template-columns (str "repeat(" limit ", 1fr)")}}
       [:li {:class (stl/css :grid-item :grid-empty-placeholder :dragged)}]]

      (= :libraries origin)
      [:div {:class (stl/css :grid-empty-placeholder :libs)
             :data-test "empty-placeholder"}
       [:div {:class (stl/css :text)}
        [:& i18n/tr-html {:label "dashboard.empty-placeholder-drafts"}]]]

      :else
      [:div
       {:class (stl/css :grid-empty-placeholder)}
       [:button {:class (stl/css :create-new)
                 :on-click on-click}
        i/add]])))

(mf/defc loading-placeholder
  []
  [:div {:class (stl/css :grid-empty-placeholder :loader)}
   [:div {:class (stl/css :icon)} i/loader]
   [:div {:class (stl/css :text)} (tr "dashboard.loading-files")]])
