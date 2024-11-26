
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.utilities.swatch
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:swatch
  [:map
   [:background :string]
   [:class {:optional true} :string]
   [:format {:optional true} [:enum "square" "rounded"]]
   [:size {:optional true} [:enum "small" "medium"]]
   [:active {:optional true} :boolean]
   [:on-click {:optional true} fn?]])

(def hex-regex #"^#(?:[0-9a-fA-F]{3}){1,2}$")
(def rgb-regex #"^rgb\((\d{1,3}),\s*(\d{1,3}),\s*(\d{1,3})\)$")
(def hsl-regex #"^hsl\((\d{1,3}),\s*(\d{1,3})%,\s*(\d{1,3})%\)$")
(def hsla-regex #"^hsla\((\d{1,3}),\s*(\d{1,3})%,\s*(\d{1,3})%,\s*(0|1|0?\.\d+)\)$")
(def rgba-regex #"^rgba\((\d{1,3}),\s*(\d{1,3}),\s*(\d{1,3}),\s*(0|1|0?\.\d+)\)$")

(defn- gradient? [background]
  (or
   (str/starts-with? background "linear-gradient")
   (str/starts-with? background "radial-gradient")))

(defn- color-solid? [background]
  (boolean
   (or (re-matches hex-regex background)
       (or (re-matches hsl-regex background)
           (re-matches rgb-regex background)))))

(defn- color-opacity? [background]
  (boolean
   (or (re-matches hsla-regex background)
       (re-matches rgba-regex background))))

(defn- extract-color-and-opacity [background]
  (cond
    (re-matches rgba-regex background)
    (let [[_ r g b a] (re-matches rgba-regex background)]
      {:color (dm/str "rgb(" r ", " g ", " b ")")
       :opacity (js/parseFloat a)})

    (re-matches hsla-regex background)
    (let [[_ h s l a] (re-matches hsla-regex background)]
      {:color (dm/str "hsl(" h ", " s "%, " l "%)")
       :opacity (js/parseFloat a)})

    :else
    {:color background
     :opacity 1.0}))

(mf/defc swatch*
  {::mf/props :obj
   ::mf/schema schema:swatch}
  [{:keys [background on-click format size active class]
    :rest props}]
  (let [element-type (if on-click "button" "div")
        button-type (if on-click "button" nil)
        format (or format "square")
        size (or size "small")
        active (or active false)
        {:keys [color opacity]} (extract-color-and-opacity background)
        class (dm/str class " " (stl/css-case
                                 :swatch true
                                 :small (= size "small")
                                 :medium (= size "medium")
                                 :square (= format "square")
                                 :active (= active true)
                                 :interactive (= element-type "button")
                                 :rounded (= format "rounded")))
        props (mf/spread-props props {:class class :on-click on-click :type button-type})]

    [:> element-type props
     (cond
       (color-solid? background)
       [:span {:class (stl/css :swatch-solid)
               :style {:background background}}]

       (color-opacity? background)
       [:span {:class (stl/css :swatch-opacity)}
        [:span {:class (stl/css :swatch-solid-side)
                :style {:background color}}]
        [:span {:class (stl/css :swatch-opacity-side)
                :style {:background color :opacity opacity}}]]

       (gradient? background)
       [:span {:class (stl/css :swatch-gradient)
               :style {:background-image (str background ", repeating-conic-gradient(lightgray 0% 25%, white 0% 50%)")}}]

       :else
       [:span {:class (stl/css :swatch-image)
               :style {:background-image (str "url('" background "'), repeating-conic-gradient(lightgray 0% 25%, white 0% 50%)")}}])]))
