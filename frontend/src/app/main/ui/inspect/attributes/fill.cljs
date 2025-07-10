;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.color :as types.color]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.inspect.attributes.common :refer [color-row]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private properties [:background :background-color :background-image])

(defn- has-fill? [shape]
  (and
   (not (contains? #{:text :group} (:type shape)))
   (or (:fill-color shape)
       (:fill-color-gradient shape)
       (seq (:fills shape)))))

(mf/defc fill-block
  {::mf/wrap-props false}
  [{:keys [objects shape]}]
  (let [format*   (mf/use-state :hex)
        format    (deref format*)
        ;; FIXME: this looks broken code, because shape does not
        ;; longer contains :fill-xxxx attributes but it is preserved
        ;; as it was just moved the impl; this need to be fixed
        color     (types.color/fill->color shape)
        on-change
        (mf/use-fn
         (fn [format]
           (reset! format* format)))]
    [:div {:class (stl/css :attributes-fill-block)}
     [:& color-row
      {:color color
       :format format
       :on-change-format on-change
       :copy-data (css/get-shape-properties-css objects {:fills [shape]} properties {:format format})}]]))

(mf/defc fill-panel
  {::mf/wrap-props false}
  [{:keys [shapes]}]
  (let [shapes (filter has-fill? shapes)]
    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title (tr "inspect.attributes.fill")
         :class (stl/css :title-spacing-fill)}]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          (if (seq (:fills shape))
            (for [value (:fills shape [])]
              [:& fill-block {:key (str "fill-block-" (:id shape) value)
                              :shape value}])
            [:& fill-block {:key (str "fill-block-only" (:id shape))
                            :shape shape}]))]])))
