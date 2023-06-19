;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.image
  (:require
   [app.common.media :as cm]
   [app.common.pages.helpers :as cph]
   [app.config :as cf]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn has-image? [shape]
  (= (:type shape) :image))

(mf/defc image-panel
  [{:keys [objects shapes]}]
  (for [shape (filter cph/image-shape? shapes)]
    [:div.attributes-block {:key (str "image-" (:id shape))}
     [:div.attributes-image-row
      [:div.attributes-image
       [:img {:src (cf/resolve-file-media (-> shape :metadata))}]]]

     [:div.attributes-unit-row
      [:div.attributes-label (tr "inspect.attributes.image.width")]
      [:div.attributes-value (css/get-css-value objects (:metadata shape) :width)]
      [:& copy-button {:data (css/get-css-property objects (:metadata shape) :width)}]]

     [:div.attributes-unit-row
      [:div.attributes-label (tr "inspect.attributes.image.height")]
      [:div.attributes-value (css/get-css-value objects (:metadata shape) :height)]
      [:& copy-button {:data (css/get-css-property objects (:metadata shape) :height)}]]

     (let [mtype     (-> shape :metadata :mtype)
           name      (:name shape)
           extension (cm/mtype->extension mtype)]
       [:a.download-button {:target "_blank"
                            :download (cond-> name extension (str/concat extension))
                            :href (cf/resolve-file-media (-> shape :metadata))}
        (tr "inspect.attributes.image.download")])]))
