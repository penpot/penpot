;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.image
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.media :as cm]
   [app.config :as cf]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn has-image? [shape]
  (= (:type shape) :image))

(mf/defc image-panel
  [{:keys [objects shapes]}]
  (for [shape (filter cfh/image-shape? shapes)]
    [:div {:class (stl/css :attributes-block)
           :key   (str "image-" (:id shape))}
     [:div {:class (stl/css :image-wrapper)}
      [:img {:src (cf/resolve-file-media (-> shape :metadata))}]]

     [:div {:class (stl/css :image-row)}
      [:div {:class (stl/css :global/attr-label)}
       (tr "inspect.attributes.image.width")]
      [:div {:class (stl/css :global/attr-value)}
       [:> copy-button* {:data (css/get-css-property objects (:metadata shape) :width)}
        [:div {:class (stl/css :button-children)} (css/get-css-value objects (:metadata shape) :width)]]]]

     [:div {:class (stl/css :image-row)}
      [:div {:class (stl/css :global/attr-label)}
       (tr "inspect.attributes.image.height")]
      [:div {:class (stl/css :global/attr-value)}
       [:> copy-button* {:data (css/get-css-property objects (:metadata shape) :height)}
        [:div {:class (stl/css :button-children)} (css/get-css-value objects (:metadata shape) :height)]]]]

     (let [mtype     (-> shape :metadata :mtype)
           name      (:name shape)
           extension (cm/mtype->extension mtype)]
       [:a   {:class (stl/css :download-button)
              :target "_blank"
              :download (cond-> name extension (str/concat extension))
              :href (cf/resolve-file-media (-> shape :metadata))}
        (tr "inspect.attributes.image.download")])]))
