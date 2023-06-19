;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.shadow
  (:require
   [app.common.data :as d]
   [app.main.ui.viewer.inspect.attributes.common :refer [color-row]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn has-shadow? [shape]
  (:shadow shape))

(mf/defc shadow-block [{:keys [shadow]}]
  (let [color-format (mf/use-state :hex)]
    [:div.attributes-shadow-block
     [:div.attributes-shadow-row
      [:div.attributes-label (->> shadow :style d/name (str "workspace.options.shadow-options.") (tr))]
      [:div.attributes-shadow {:title  (tr "workspace.options.shadow-options.offsetx")}
       [:div.attributes-value (str (:offset-x shadow) "px")]]

      [:div.attributes-shadow {:title  (tr "workspace.options.shadow-options.offsety")}
       [:div.attributes-value (str (:offset-y shadow) "px")]]

      [:div.attributes-shadow {:title  (tr "workspace.options.shadow-options.blur")}
       [:div.attributes-value (str (:blur shadow) "px")]]

      [:div.attributes-shadow {:title  (tr "workspace.options.shadow-options.spread")}
       [:div.attributes-value (str (:spread shadow) "px")]]

      #_[:& copy-button {:data (shadow-copy-data shadow)}]]

     [:& color-row {:color (:color shadow)
                    :format @color-format
                    :on-change-format #(reset! color-format %)}]]))

(mf/defc shadow-panel [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-shadow?))]
    (when (and (seq shapes) (> (count shapes) 0))
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (tr "inspect.attributes.shadow")]]

       [:div.attributes-shadow-blocks
        (for [shape shapes]
          (for [shadow (:shadow shape)]
            [:& shadow-block {:shape shape
                              :shadow shadow}]))]])))
