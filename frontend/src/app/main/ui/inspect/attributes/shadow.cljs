;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.inspect.attributes.common :refer [color-row]]
   [app.util.code-gen.style-css :as css]
   [app.util.code-gen.style-css-formats :refer [format-color-value]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- has-shadow? [shape]
  (:shadow shape))

(defn- shadow-copy-data [shadow]
  (css/shadow->css shadow))

(defn- copy-color-data
  "Converts a fill object to CSS color string in the specified format."
  [color format]
  (format-color-value color {:format format}))

(mf/defc shadow-block [{:keys [shadow]}]
  (let [color-format (mf/use-state :hex)
        color-format* (deref color-format)
        on-change-format
        (mf/use-fn
         (fn [format]
           (reset! color-format format)))]
    [:div {:class (stl/css :attributes-shadow-block)}
     [:div {:class (stl/css :shadow-row)}
      [:div {:class (stl/css :global/attr-label)} (->> shadow :style d/name (str "workspace.options.shadow-options.") (tr))]
      [:div {:class (stl/css :global/attr-value)}
       [:> copy-button* {:data  (shadow-copy-data shadow)
                         :class (stl/css :color-row-copy-btn)}
        [:div  {:class (stl/css :button-children)
                :title  (dm/str (tr "workspace.options.shadow-options.offsetx") " "
                                (tr "workspace.options.shadow-options.offsety") " "
                                (tr "workspace.options.shadow-options.blur") " "
                                (tr "workspace.options.shadow-options.spread"))}
         (str (:offset-x shadow) "px") " "
         (str (:offset-y shadow) "px") " "
         (str (:blur shadow) "px") " "
         (str (:spread shadow) "px")]]]]

     [:& color-row {:color (:color shadow)
                    :format @color-format
                    :copy-data (copy-color-data (:color shadow) color-format*)
                    :on-change-format on-change-format}]]))

(mf/defc shadow-panel [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-shadow?))]

    (when (and (seq shapes) (> (count shapes) 0))
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title (tr "inspect.attributes.shadow")
         :class (stl/css :title-spacing-shadow)}]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          (for [shadow (:shadow shape)]
            [:& shadow-block {:shape shape
                              :key   (dm/str "block-" (:id shape) "-shadow")
                              :shadow shadow}]))]])))
