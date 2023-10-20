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
   [app.main.ui.context :as ctx]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn map->css [attr]
  (->> attr
       (map (fn [[attr-key attr-value]] (str (d/name attr-key) ":" attr-value)))
       (str/join "; ")))

(mf/defc svg-attr [{:keys [attr value]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system

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
           [:div {:class (stl/css :button-children)} (str value)]]]])


      (if (map? value)
        [:*
         [:div.attributes-block-title
          [:div.attributes-block-title-text (d/name attr)]
          [:& copy-button {:data (map->css value)}]]

         (for [[attr-key attr-value] value]
           [:& svg-attr {:attr  attr-key :value attr-value}])]

        [:div.attributes-unit-row
         [:div.attributes-label (d/name attr)]
         [:div.attributes-value (str value)]
         [:& copy-button {:data (d/name value)}]]))))

(mf/defc svg-block
  [{:keys [shape]}]
  [:*
   (for [[attr-key attr-value] (:svg-attrs shape)]
     [:& svg-attr {:attr  attr-key :value attr-value}])]  )


(mf/defc svg-panel
  [{:keys [shapes]}]

  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        shape (first shapes)]
    (if new-css-system
      (when (seq (:svg-attrs shape))
        [:div {:class (stl/css :attributes-block)}
         [:& title-bar {:collapsable? false
                        :title        (tr "workspace.sidebar.options.svg-attrs.title")
                        :class        (stl/css :title-spacing-svg)}]
         [:& svg-block {:shape shape}]])


      (when (seq (:svg-attrs shape))
        [:div.attributes-block
         [:div.attributes-block-title
          [:div.attributes-block-title-text (tr "workspace.sidebar.options.svg-attrs.title")]]
         [:& svg-block {:shape shape}]]))))
