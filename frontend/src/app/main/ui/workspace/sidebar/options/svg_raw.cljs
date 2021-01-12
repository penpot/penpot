;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.svg-raw
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.data :as d]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-menu]]))

;; This is a list of svg tags that can be grouped in shape-container
;; this allows them to have gradients, shadows and masks
(def svg-elements #{:svg :circle :ellipse :image :line :path :polygon :polyline :rect :symbol :text :textPath})

(defn hex->number [hex] 1)
(defn shorthex->longhex [hex]
  (let [[_ r g b] hex]
    (str "#" r r g g b b)))

(defn parse-color [color]
  (cond
    (or (not color) (= color "none")) nil

    (and (str/starts-with? color "#") (= (count color) 4))
    {:color (shorthex->longhex color)
     :opacity 1}

    (and (str/starts-with? color "#") (= (count color) 9))
    {:color (subs color 1 6)
     :opacity (-> (subs color 7 2) (hex->number))}

    ;; TODO CHECK IF IT'S A GRADIENT

    (str/starts-with? color "url")
    {:color :multiple
     :opacity :multiple}

    :else nil))


(defn get-fill-values [shape]
  (let [fill-values (or (select-keys shape fill-attrs))
        color (-> (get-in shape [:content :attrs :fill])
                  (parse-color))

        fill-values (if (and (empty? fill-values) color)
                      {:fill-color (:color color)
                       :fill-opacity (:opacity color)}
                      fill-values)]
    fill-values))

(defn get-stroke-values [shape]
  (let [stroke-values (or (select-keys shape stroke-attrs))
        color (-> (get-in shape [:content :attrs :stroke])
                  (parse-color))

        stroke-color (:color color "#000000")
        stroke-opacity (:opacity color 1)
        stroke-style (-> (get-in shape [:content :attrs :stroke-style] (if color "solid" "none"))
                         keyword)
        stroke-alignment :center
        stroke-width (-> (get-in shape [:content :attrs :stroke-width] "1")
                         (d/parse-int))

        stroke-values (if (empty? stroke-values)
                        {:stroke-color stroke-color
                         :stroke-opacity stroke-opacity
                         :stroke-style stroke-style
                         :stroke-alignment stroke-alignment
                         :stroke-width stroke-width}

                        stroke-values)]
    stroke-values))

(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]

  (let [ids [(:id shape)]
        type (:type shape)
        {:keys [tag attrs] :as content} (:content shape)
        measure-values (select-keys shape measure-attrs)
        fill-values    (get-fill-values shape)
        stroke-values  (get-stroke-values shape)]

    (when (contains? svg-elements tag)
      [:*
       (when (= tag :svg)
         [:*
          [:& measures-menu {:ids ids
                             :type type
                             :values measure-values}]])

       [:& fill-menu {:ids ids
                      :type type
                      :values fill-values}]
       [:& stroke-menu {:ids ids
                        :type type
                        :values stroke-values}]
       [:& shadow-menu {:ids ids
                        :values (select-keys shape [:shadow])}]

       [:& blur-menu {:ids ids
                      :values (select-keys shape [:blur])}]])))
