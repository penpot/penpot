;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.svg-raw
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.svg-attrs :refer [svg-attrs-menu]]
   [app.util.color :as uc]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; This is a list of svg tags that can be grouped in shape-container
;; this allows them to have gradients, shadows and masks
(def svg-elements #{:svg :g :circle :ellipse :image :line :path :polygon :polyline :rect :symbol :text :textPath})

(defn hex->number [_] 1)

(defn shorthex->longhex [hex]
  (let [[_ r g b] hex]
    (str "#" r r g g b b)))

(defn parse-color [color]
  (try
    (cond
      (or (not color) (= color "none")) nil

      ;; TODO CHECK IF IT'S A GRADIENT
      (str/starts-with? color "url")
      {:color :multiple
       :opacity :multiple}

      :else {:color (uc/parse-color color)
             :opacity 1})

    (catch :default e
      (.error js/console "Error parsing color" e)
      nil)))


(defn get-fill-values [shape]
  (let [fill-values (select-keys shape fill-attrs)
        color       (-> (or (get-in shape [:content :attrs :fill])
                            (get-in shape [:content :attrs :style :fill]))
                        (parse-color))

        fill-values (if (and (empty? fill-values) color)
                      {:fill-color (:color color)
                       :fill-opacity (:opacity color)}
                      fill-values)]
    fill-values))

(defn get-stroke-values [shape]
  (let [stroke-values (select-keys shape stroke-attrs)
        color         (-> (or (get-in shape [:content :attrs :stroke])
                              (get-in shape [:content :attrs :style :stroke]))
                          (parse-color))

        stroke-color (:color color clr/black)
        stroke-opacity (:opacity color 1)
        stroke-style (-> (or (get-in shape [:content :attrs :stroke-style])
                             (get-in shape [:content :attrs :style :stroke-style])
                             (if color "solid" "none"))
                         keyword)
        stroke-alignment :center
        stroke-width (-> (or (get-in shape [:content :attrs :stroke-width])
                             (get-in shape [:content :attrs :style :stroke-width])
                             "1")
                         (d/parse-integer))

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

        {:keys [tag] :as content} (:content shape)
        measure-values (select-keys shape measure-attrs)
        constraint-values (select-keys shape constraint-attrs)
        fill-values    (get-fill-values shape)
        stroke-values  (get-stroke-values shape)
        layout-item-values (select-keys shape layout-item-attrs)
        layout-container-values (select-keys shape layout-container-flex-attrs)

        is-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/is-layout-child? ids))
        is-layout-child? (mf/deref is-layout-child-ref)
        is-layout-child-absolute? (ctl/layout-absolute? shape)]

    (when (contains? svg-elements tag)
      [:*
       [:& measures-menu {:ids ids
                          :type type
                          :values measure-values
                          :shape shape}]
       [:& layout-container-menu {:type type :ids [(:id shape)] :values layout-container-values :multiple false}]

       (when is-layout-child?
         [:& layout-item-menu
          {:ids ids
           :type type
           :values layout-item-values
           :is-layout-child? true
           :shape shape}])

       (when (or (not is-layout-child?) is-layout-child-absolute?)
         [:& constraints-menu {:ids ids
                               :values constraint-values}])

       [:& fill-menu {:ids ids
                      :type type
                      :values fill-values}]

       [:& stroke-menu {:ids ids
                        :type type
                        :values stroke-values}]

       [:& shadow-menu {:ids ids
                        :values (select-keys shape [:shadow])}]

       [:& blur-menu {:ids ids
                      :values (select-keys shape [:blur])}]

       [:& svg-attrs-menu {:ids ids
                           :values (select-keys shape [:svg-attrs])}]])))
