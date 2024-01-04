;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.blur
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn has-blur? [shape]
  (:blur shape))

(mf/defc blur-panel
  [{:keys [objects shapes]}]
  (let [shapes (->> shapes (filter has-blur?))]
    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:& title-bar {:collapsable? false
                      :title        (tr "inspect.attributes.blur")
                      :class        (stl/css :title-spacing-blur)}
        (when (= (count shapes) 1)
          [:& copy-button {:data (css/get-css-property objects (first shapes) :filter)}])]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          [:div {:class (stl/css :blur-row)}
           [:div {:class (stl/css :global/attr-label)} "Filter"]
           [:div {:class (stl/css :global/attr-value)}
            [:& copy-button {:data (css/get-css-property objects shape :filter)}
             [:div {:class (stl/css :button-children)}
              (css/get-css-value objects shape :filter)]]]])]])))
