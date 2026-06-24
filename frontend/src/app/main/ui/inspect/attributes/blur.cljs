;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.attributes.blur
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.features :as features]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- has-blur? [shape]
  (or (:blur shape)
      (:background-blur shape)))

(mf/defc blur-panel
  [{:keys [objects shapes]}]
  (let [render-wasm?        (features/use-feature "render-wasm/v1")
        bg-blur?            (and render-wasm?
                                 (contains? cf/flags :background-blur))
        shapes (->> shapes (filter #(has-blur? %)))
        title (if bg-blur?
                (tr "labels.blur-effects")
                (tr "labels.blur"))]
    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title title
         :class (stl/css :title-wrapper)
         :title-class (stl/css :blur-attr-title)}
        (when (= (count shapes) 1)
          (let [background-blur (:background-blur (first shapes))
                layer-blur (:blur (first shapes))]
            (when background-blur
              [:> copy-button* {:data  (css/get-css-property objects (first shapes) :backdrop-filter)
                                :class (stl/css :copy-btn-title)}])
            (when layer-blur
              [:> copy-button* {:data  (css/get-css-property objects (first shapes) :filter)
                                :class (stl/css :copy-btn-title)}])))]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          (let [background-blur (:background-blur (first shapes))
                layer-blur (:blur (first shapes))]
            [:div {:class (stl/css :blur-row)
                   :key (dm/str "block-" (:id shape) "-blur")}
             (when background-blur
               [:div {:key (dm/str "block-" (:id shape) "-background-blur")}
                [:div {:class (stl/css :global/attr-label)}
                 "Backdrop Filter"]
                [:div {:class (stl/css :global/attr-value)}
                 [:> copy-button* {:data (css/get-css-property objects shape :backdrop-filter)}
                  [:div {:class (stl/css :button-children)}
                   (css/get-css-value objects shape :backdrop-filter)]]]])
             (when layer-blur
               [:div {:key (dm/str "block-" (:id shape) "-layer-blur")}
                [:div {:class (stl/css :global/attr-label)}
                 "Filter"]
                [:div {:class (stl/css :global/attr-value)}
                 [:> copy-button* {:data (css/get-css-property objects shape :filter)}
                  [:div {:class (stl/css :button-children)}
                   (css/get-css-value objects shape :filter)]]]])]))]])))
