;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
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
   :file-id (:fill-color-ref-file shape)
   :image (:fill-image shape)})

(defn has-fill? [shape]
  (and
   (not (contains? #{:text :group} (:type shape)))
   (or (:fill-color shape)
       (:fill-color-gradient shape)
       (seq (:fills shape)))))

(mf/defc fill-block
  {::mf/wrap-props false}
  [{:keys [objects shape]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        format*   (mf/use-state :hex)
        format    (deref format*)

        color     (shape->color shape)
        on-change
        (mf/use-fn
         (fn [format]
           (reset! format* format)))]
    (if new-css-system
      [:div {:class (stl/css :attributes-fill-block)}
       [:& color-row
        {:color color
         :format format
         :on-change-format on-change
         :copy-data (css/get-shape-properties-css objects {:fills [shape]} properties)}]]


      [:div.attributes-fill-block
       [:& color-row
        {:color color
         :format format
         :on-change-format on-change
         :copy-data (css/get-shape-properties-css objects {:fills [shape]} properties)}]])))

(mf/defc fill-panel
  {::mf/wrap-props false}
  [{:keys [shapes]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        shapes (filter has-fill? shapes)]
    (if new-css-system
      (when (seq shapes)
        [:div {:class (stl/css :attributes-block)}
          [:& title-bar {:collapsable? false
                        :title        (tr "inspect.attributes.fill")
                        :class        (stl/css :title-spacing-fill)}]

         [:div {:class (stl/css :attributes-content)}
          (for [shape shapes]
            (if (seq (:fills shape))
              (for [value (:fills shape [])]
                [:& fill-block {:key (str "fill-block-" (:id shape) value)
                                :shape value}])
              [:& fill-block {:key (str "fill-block-only" (:id shape))
                              :shape shape}]))]])


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
                             :shape shape}]))]]))))
