;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.stroke
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.inspect.attributes.common :refer [color-row]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def properties [:border])

(defn stroke->color [shape]
  {:color (:stroke-color shape)
   :opacity (:stroke-opacity shape)
   :gradient (:stroke-color-gradient shape)
   :id (:stroke-color-ref-id shape)
   :file-id (:stroke-color-ref-file shape)
   :image (:stroke-image shape)})

(defn has-stroke? [shape]
  (seq (:strokes shape)))

(mf/defc stroke-block
  {::mf/wrap-props false}
  [{:keys [objects shape]}]
  (let [format*   (mf/use-state :hex)
        format    (deref format*)
        color     (stroke->color shape)
        on-change
        (mf/use-fn
         (fn [format]
           (reset! format* format)))]
    [:div {:class (stl/css :attributes-fill-block)}
     [:& color-row
      {:color color
       :format format
       :on-change-format on-change
       :copy-data (css/get-shape-properties-css objects {:strokes [shape]} properties)}]]))

(mf/defc stroke-panel
  [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-stroke?))]
    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title (tr "inspect.attributes.stroke")
         :class (stl/css :title-spacing-stroke)}]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          (for [value (:strokes shape)]
            [:& stroke-block {:key (str "stroke-color-" (:id shape) value)
                              :shape value}]))]])))
