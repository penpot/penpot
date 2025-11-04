;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.common
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.media :as cm]
   [app.common.types.color :as cc]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.inspect.common.colors :as isc]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn get-css-rule-humanized [property]
  (as-> property $
    (d/name $)
    (str/split $ "-")
    (str/join " " $)
    (str/capital $)))

(mf/defc color-row [{:keys [color format copy-data on-change-format]}]
  (let [colors-library     (isc/use-colors-library color)
        file-colors-ref (mf/deref isc/file-colors-ref)
        file-colors-wokspace (mf/deref refs/workspace-file-colors)
        file-colors (or file-colors-ref file-colors-wokspace)
        color-library-name (get-in (or colors-library file-colors) [(:ref-id color) :name])
        color              (assoc color :name color-library-name)
        image              (:image color)]


    (if image
      (let [mtype     (-> image :mtype)
            name      (or (:name image) (tr "media.image"))
            extension (cm/mtype->extension mtype)]
        [:div {:class (stl/css :attributes-image-as-color-row)}
         [:div {:class (stl/css :attributes-color-row)}
          [:div {:class (stl/css :bullet-wrapper)
                 :style #js {"--bullet-size" "16px"}}
           [:& cb/color-bullet {:color color
                                :mini true}]]

          [:div {:class (stl/css :format-wrapper)}
           [:div {:class (stl/css :image-format)}
            (tr "media.image.short")]]
          [:> copy-button* {:data copy-data
                            :class (stl/css :color-row-copy-btn)}
           [:div {:class (stl/css-case :color-info true
                                       :two-line (some? color-library-name))}
            [:div {:class (stl/css :first-row)}
             [:span {:class (stl/css :opacity-info)}
              (str (* 100 (:opacity color)) "%")]]

            (when color-library-name
              [:div {:class (stl/css :second-row)}
               [:div {:class (stl/css :color-name-library)
                      :data-testid "color-library-name"}
                color-library-name]])]]

          [:div {:class (stl/css :image-download)}
           [:div {:class (stl/css :image-wrapper)}
            [:img {:src (cf/resolve-file-media image)}]]

           [:a {:class (stl/css :download-button)
                :target "_blank"
                :download (cond-> name extension (str/concat extension))
                :href (cf/resolve-file-media image)}
            (tr "inspect.attributes.image.download")]]]])

      [:div {:class (stl/css :attributes-color-row)}
       [:div {:class (stl/css :bullet-wrapper)
              :style #js {"--bullet-size" "16px"}}
        [:& cb/color-bullet {:color color
                             :mini true}]]

       [:div {:class (stl/css :format-wrapper)}
        (when-not (and on-change-format (or (:gradient color) image))
          [:& select
           {:default-value format
            :class (stl/css :select-format-wrapper)
            :options [{:value :hex :label (tr "inspect.attributes.color.hex")}
                      {:value :rgba :label (tr "inspect.attributes.color.rgba")}
                      {:value :hsla :label (tr "inspect.attributes.color.hsla")}]
            :on-change on-change-format}])
        (when (:gradient color)
          [:div {:class (stl/css :format-info)} "rgba"])]

       [:> copy-button* {:data copy-data
                         :aria-label (tr "labels.copy-color")
                         :class (stl/css-case :color-row-copy-btn true
                                              :one-line (not color-library-name)
                                              :two-line (some? color-library-name))}
        [:div {:class (stl/css :first-row)}
         [:div {:class (stl/css :name-opacity)}
          [:span {:class (stl/css-case :color-value-wrapper true
                                       :gradient-name (:gradient color))}
           (if (:gradient color)
             [:& cb/color-name {:color color :size 90}]
             (case format
               :hex [:& cb/color-name {:color color}]
               :rgba (let [[r g b a] (cc/hex->rgba (:color color) (:opacity color))
                           result (cc/format-rgba [r g b a])]
                       [:* result])
               :hsla (let [[h s l a] (cc/hex->hsla (:color color) (:opacity color))
                           result (cc/format-hsla [h s l a])]
                       [:* result])))]

          (when (and (not (:gradient color)) (= :hex format))
            [:span {:class (stl/css :opacity-info)}
             (dm/str (-> color
                         (:opacity)
                         (d/coalesce 1)
                         (* 100)
                         (fmt/format-number)) "%")])]]

        (when color-library-name
          [:div {:class (stl/css :second-row)}
           [:div {:class (stl/css :color-name-library)
                  :data-testid "color-library-name"}
            color-library-name]])]])))

