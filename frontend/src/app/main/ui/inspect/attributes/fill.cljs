;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.fills :as types.fills]
   [app.config :as cf]
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

;; DEPRECATED, use fill-block-styles* instead.
;; This component is kept for backward compatibility
(mf/defc fill-block
  {::mf/wrap-props false}
  [{:keys [objects shape]}]
  (let [format*   (mf/use-state :hex)
        format    (deref format*)
        ;; FIXME: this looks broken code, because shape does not
        ;; longer contains :fill-xxxx attributes but it is preserved
        ;; as it was just moved the impl; this need to be fixed
        color     (types.fills/fill->color shape)
        on-change
        (mf/use-fn
         (fn [format]
           (reset! format* format)))]
    [:div {:class (stl/css :attributes-fill-block)}
     [:& color-row
      {:color color
       :format format
       :property "Background"
       :on-change-format on-change
       :copy-data (css/get-shape-properties-css objects {:fills [shape]} properties {:format format})}]]))

;; New implementation of fill-block using the new color format selector
;; This component is used when the flag :inspect-styles is enabled. Update when flag no longer needed.
(mf/defc fill-block-styles*
  [{:keys [objects shape color-space]}]
  (let [color     (types.fills/fill->color shape)]
    [:div {:class (stl/css :attributes-fill-block)}
     [:& color-row
      {:color color
       :property "Background"
       :format (d/nilv (keyword color-space) :hex)
       :copy-data (css/get-shape-properties-css objects {:fills [shape]} properties {:format (keyword color-space)})}]]))


(mf/defc fill-panel*
  [{:keys [shapes color-space]}]
  (let [shapes (filter has-fill? shapes)]
    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title (tr "inspect.attributes.fill")
         :class (stl/css :title-spacing-fill)}]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          (if (contains? cf/flags :inspect-styles)
            (if (seq (:fills shape))
              (for [value (:fills shape [])]
                [:> fill-block-styles* {:key (str "fill-block-" (:id shape) value)
                                        :color-space color-space
                                        :shape value}])
              [:> fill-block-styles* {:key (str "fill-block-only" (:id shape))
                                      :color-space color-space
                                      :shape shape}])
            (if (seq (:fills shape))
              (for [value (:fills shape [])]
                [:& fill-block {:key (str "fill-block-" (:id shape) value)
                                :shape value}])
              [:& fill-block {:key (str "fill-block-only" (:id shape))
                              :shape shape}])))]])))
