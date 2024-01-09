;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.svg
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn map->css [attr]
  (->> attr
       (map (fn [[attr-key attr-value]] (str (d/name attr-key) ":" attr-value)))
       (str/join "; ")))

(mf/defc svg-attr [{:keys [attr value]}]
  (if (map? value)
    [:*
     [:div {:class (stl/css :attributes-subtitle)}
      [:span (d/name attr)]
      [:& copy-button {:data (map->css value)}]]

     (for [[attr-key attr-value] value]
       [:& svg-attr {:attr  attr-key :value attr-value}])]

    [:div {:class (stl/css :svg-row)}
     [:div {:class (stl/css :global/attr-label)} (d/name attr)]
     [:div {:class (stl/css :global/attr-value)}
      [:& copy-button {:data (d/name value)}
       [:div {:class (stl/css :button-children)} (str value)]]]]))

(mf/defc svg-block
  [{:keys [shape]}]
  [:*
   (for [[attr-key attr-value] (:svg-attrs shape)]
     [:& svg-attr {:attr  attr-key :value attr-value}])])


(mf/defc svg-panel
  [{:keys [shapes]}]
  (let [shape (first shapes)]
    (when (seq (:svg-attrs shape))
      [:div {:class (stl/css :attributes-block)}
       [:& title-bar {:collapsable false
                      :title       (tr "workspace.sidebar.options.svg-attrs.title")
                      :class       (stl/css :title-spacing-svg)}]
       [:& svg-block {:shape shape}]])))
