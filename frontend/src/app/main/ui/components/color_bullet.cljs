;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.color-bullet
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cfg]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- color-title
  [color-item]
  (let [name (:name color-item)
        path (:path color-item)
        path-and-name (if path (str path " / " name) name)
        gradient (:gradient color-item)
        image (:image color-item)
        color (:color color-item)]

    (if (some? name)
      (cond
        (some? color)
        (str/ffmt "% (%)" path-and-name color)

        (some? gradient)
        (str/ffmt "% (%)" path-and-name (uc/gradient-type->string (:type gradient)))

        (some? image)
        (str/ffmt "% (%)" path-and-name (tr "media.image"))

        :else
        path-and-name)

      (cond
        (some? color)
        color

        (some? gradient)
        (uc/gradient-type->string (:type gradient))

        (some? image)
        (tr "media.image")))))

(defn- breakable-color-title
  [title]
  (str/replace title "." ".\u200B"))

(mf/defc color-bullet
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [color on-click mini area]}]
  (let [read-only? (nil? on-click)
        on-click
        (mf/use-fn
         (mf/deps color on-click)
         (fn [event]
           (when (fn? on-click)
             (^function on-click color event))))]

    (if (uc/multiple? color)
      [:div {:class (stl/css :color-bullet :multiple)
             :on-click on-click
             :title (color-title color)}]
      ;; No multiple selection
      (let [color    (if (string? color) {:color color :opacity 1} color)
            id       (or (:ref-id color) (:id color))
            gradient (:gradient color)
            opacity  (:opacity color)
            image    (:image color)]
        [:div
         {:class (stl/css-case
                  :color-bullet true
                  :mini mini
                  :is-library-color (some? id)
                  :is-not-library-color (nil? id)
                  :is-gradient (some? gradient)
                  :is-transparent (and opacity (> 1 opacity))
                  :grid-area area
                  :read-only read-only?)
          :role "button"
          :data-readonly (str read-only?)
          :on-click on-click
          :title (color-title color)}

         (cond
           (some? gradient)
           [:div {:class (stl/css :color-bullet-wrapper)
                  :style {:background (uc/color->background color)}}]

           (some? image)
           (let [uri (cfg/resolve-file-media image)]
             [:div {:class (stl/css :color-bullet-wrapper)
                    :style {:background-image (str/ffmt "url(%)" uri)}}])

           :else
           [:div {:class (stl/css :color-bullet-wrapper)}
            [:div {:class (stl/css :color-bullet-left)
                   :style {:background (uc/color->background (assoc color :opacity 1))}}]
            [:div {:class (stl/css :color-bullet-right)
                   :style {:background (uc/color->background color)}}]])]))))

(mf/defc color-name
  {::mf/wrap-props false}
  [{:keys [color size on-click on-double-click origin]}]
  (let [{:keys [name color gradient]} (if (string? color) {:color color :opacity 1} color)]
    (when (or (not size) (> size 64))
      [:span {:class (stl/css-case
                      :color-text (and (= origin :palette) (< size 72))
                      :small-text (and (= origin :palette) (>= size 64) (< size 72))
                      :big-text   (and (= origin :palette) (>= size 72))
                      :gradient   (some? gradient)
                      :color-row-name (not=  origin :palette))
              :title name
              :on-click on-click
              :on-double-click on-double-click}
       (breakable-color-title (or name color (uc/gradient-type->string (:type gradient))))])))
