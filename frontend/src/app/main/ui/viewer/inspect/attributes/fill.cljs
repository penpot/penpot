;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.fill
  (:require
   [app.main.ui.viewer.inspect.attributes.common :refer [color-row]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def properties [:background :background-color :background-image])

(defn shape->color [shape]
  {:color (:fill-color shape)
   :opacity (:fill-opacity shape)
   :gradient (:fill-color-gradient shape)
   :id (:fill-color-ref-id shape)
   :file-id (:fill-color-ref-file shape)})

(defn has-fill? [shape]
  (and
   (not (contains? #{:text :group} (:type shape)))
   (or (:fill-color shape)
       (:fill-color-gradient shape)
       (seq (:fills shape)))))

(mf/defc fill-block
  [{:keys [objects shape]}]
  (let [color-format (mf/use-state :hex)
        color (shape->color shape)]

    [:div.attributes-fill-block
     [:& color-row {:color color
                    :format @color-format
                    :on-change-format #(reset! color-format %)
                    :copy-data (css/get-shape-properties-css objects {:fills [shape]} properties)}]]))

(mf/defc fill-panel
  [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-fill?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (tr "inspect.attributes.fill")]]

       [:div.attributes-fill-blocks
        (for [shape shapes]
         (if (seq (:fills shape))
           (for [value (:fills shape [])]
             [:& fill-block {:key (str "fill-block-" (:id shape) value)
                             :shape value}])
           [:& fill-block {:key (str "fill-block-only" (:id shape))
                           :shape shape}]))]])))
