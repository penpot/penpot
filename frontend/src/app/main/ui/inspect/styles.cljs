;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.refs :as refs]
   [app.main.ui.inspect.styles.panels.blur :refer [blur-panel*]]
   [app.main.ui.inspect.styles.panels.fill :refer [fill-panel*]]
   [app.main.ui.inspect.styles.panels.geometry :refer [geometry-panel*]]
   [app.main.ui.inspect.styles.panels.layout :refer [layout-panel*]]
   [app.main.ui.inspect.styles.panels.layout-element :refer [layout-element-panel*]]
   [app.main.ui.inspect.styles.panels.shadow :refer [shadow-panel*]]
   [app.main.ui.inspect.styles.panels.stroke :refer [stroke-panel*]]
   [app.main.ui.inspect.styles.panels.svg :refer [svg-panel*]]
   [app.main.ui.inspect.styles.panels.text :refer [text-panel*]]
   [app.main.ui.inspect.styles.panels.tokens-panel :refer [tokens-panel*]]
   [app.main.ui.inspect.styles.panels.variants-panel :refer [variants-panel*]]
   [app.main.ui.inspect.styles.panels.visibility :refer [visibility-panel*]]
   [app.main.ui.inspect.styles.style-box :refer [style-box*]]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layout-element-properties
  [:margin-block-start
   :margin-block-end
   :margin-inline-start
   :margin-inline-end
   :max-block-size
   :min-block-size
   :max-inline-size
   :min-inline-size
   :align-self
   :justify-self
   :flex-shrink
   :flex

   ;; Grid cell properties
   :grid-column
   :grid-row])


(def type->panel-group
  {:multiple [:fill :stroke :text :shadow :blur :layout-element]
   :frame    [:visibility :geometry :fill :stroke :shadow :blur :layout :layout-element]
   :group    [:visibility :geometry :svg :layout-element]
   :rect     [:visibility :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :circle   [:visibility :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :path     [:visibility :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :text     [:visibility :geometry :text :shadow :blur :stroke :layout-element]
   :variant  [:variant :geometry :fill :stroke :shadow :blur :layout :layout-element]})

(defn- has-fill?
  [shape]
  (and
   (not (contains? #{:text :group} (:type shape)))
   (seq (:fills shape))))

(defn- has-stroke? [shape]
  (seq (:strokes shape)))

(defn- has-blur? [shape]
  (:blur shape))

(defn- has-text? [shape]
  (:content shape))

(defn- has-shadow? [shape]
  (seq (:shadow shape)))

(defn- get-shape-type
  [shapes first-shape first-component]
  (if (= (count shapes) 1)
    (if (or (ctc/is-variant-container? first-shape)
            (ctc/is-variant? first-component))
      :variant

      (:type first-shape))
    :multiple))

(mf/defc styles-tab*
  [{:keys [color-space shapes libraries objects file-id]}]
  (let [data               (dm/get-in libraries [file-id :data])
        first-shape        (first shapes)
        first-component    (ctkl/get-component data (:component-id first-shape))
        shape-type         (get-shape-type shapes first-shape first-component)
        panels             (type->panel-group shape-type)

        tokens-lib         (mf/deref refs/tokens-lib)
        active-themes      (mf/deref refs/workspace-active-theme-paths-no-hidden)
        active-sets        (mf/with-memo [tokens-lib]
                             (some-> tokens-lib (ctob/get-active-themes-set-names)))
        active-tokens      (mf/with-memo [tokens-lib]
                             (some-> tokens-lib (ctob/get-tokens-in-active-sets)))
        resolved-active-tokens (sd/use-resolved-tokens* active-tokens)
        has-visibility-props? (mf/use-fn
                               (fn [shape]
                                 (let [shape-type (:type shape)]
                                   (and
                                    (not (or (= shape-type :text) (= shape-type :group)))
                                    (or (:opacity shape)
                                        (:blend-mode shape)
                                        (:visibility shape))))))
        shorthands* (mf/use-state #(do {:fill nil
                                        :stroke nil
                                        :text nil
                                        :shadow nil
                                        :blur nil
                                        :layout nil
                                        :layout-element nil
                                        :geometry nil
                                        :svg nil
                                        :visibility nil
                                        :variant nil
                                        :grid-element nil}))
        shorthands (deref shorthands*)
        set-shorthands
        ;; This fn must receive an object `shorthand` with :panel and :property (the shorthand string) keys
        (mf/use-fn
         (mf/deps shorthands*)
         (fn [shorthand]
           (swap! shorthands* assoc (:panel shorthand) (:property shorthand))))]
    [:ol {:class (stl/css :styles-tab) :aria-label (tr "labels.styles")}
     ;;  TOKENS PANEL
     (when (or (seq active-themes) (seq active-sets))
       [:li
        [:> style-box* {:panel :token}
         [:> tokens-panel* {:theme-paths active-themes :set-names active-sets}]]])
     (for [panel panels]
       [:li {:key (d/name panel)}
        (case panel
        ;;  VARIANTS PANEL
          :variant
          [:> style-box* {:panel :variant}
           [:> variants-panel* {:component first-component
                                :objects objects
                                :shape first-shape
                                :data data}]]
        ;;  GEOMETRY PANEL
          :geometry
          [:> style-box* {:panel :geometry
                          :shorthand (:geometry shorthands)}
           [:> geometry-panel* {:shapes shapes
                                :objects objects
                                :resolved-tokens resolved-active-tokens
                                :on-geometry-shorthand set-shorthands}]]
         ;;  LAYOUT PANEL
          :layout
          (let [layout-shapes (->> shapes (filter ctl/any-layout?))]
            (when (seq layout-shapes)
              [:> style-box* {:panel :layout
                              :shorthand (:layout shorthands)}
               [:> layout-panel* {:shapes layout-shapes
                                  :objects objects
                                  :resolved-tokens resolved-active-tokens
                                  :on-layout-shorthand set-shorthands}]]))
         ;;  LAYOUT ELEMENT PANEL
          :layout-element
          (let [shapes (->> shapes (filter #(ctl/any-layout-immediate-child? objects %)))
                some-layout-prop? (->> shapes
                                       (mapcat (fn [shape]
                                                 (keep #(css/get-css-value objects shape %) layout-element-properties)))
                                       (seq))]
            (when some-layout-prop?
              (let [only-flex? (every? #(ctl/flex-layout-immediate-child? objects %) shapes)
                    only-grid? (every? #(ctl/grid-layout-immediate-child? objects %) shapes)
                    panel (if only-flex?
                            :flex-element
                            (if only-grid?
                              :grid-element
                              :layout-element))]
                [:> style-box* {:panel panel
                                :shorthand (:layout-element shorthands)}
                 [:> layout-element-panel* {:shapes shapes
                                            :objects objects
                                            :resolved-tokens resolved-active-tokens
                                            :layout-element-properties layout-element-properties
                                            :on-layout-element-shorthand set-shorthands}]])))
          ;; FILL PANEL
          :fill
          (let [shapes (filter has-fill? shapes)]
            (when (seq shapes)
              [:> style-box* {:panel :fill
                              :shorthand (:fill shorthands)}
               [:> fill-panel* {:color-space color-space
                                :shapes shapes
                                :resolved-tokens resolved-active-tokens
                                :on-fill-shorthand set-shorthands}]]))

          ;; STROKE PANEL
          :stroke
          (let [shapes (filter has-stroke? shapes)]
            (when (seq shapes)
              [:> style-box* {:panel :stroke
                              :shorthand (:stroke shorthands)}
               [:> stroke-panel* {:color-space color-space
                                  :shapes shapes
                                  :objects objects
                                  :resolved-tokens resolved-active-tokens
                                  :on-stroke-shorthand set-shorthands}]]))

          ;; VISIBILITY PANEL
          :visibility
          (let [shapes (filter has-visibility-props? shapes)]
            (when (seq shapes)
              [:> style-box* {:panel :visibility}
               [:> visibility-panel* {:shapes shapes
                                      :objects objects
                                      :resolved-tokens resolved-active-tokens}]]))
          ;; SVG PANEL
          :svg
          (let [shape (first shapes)]
            (when (seq (:svg-attrs shape))
              [:> style-box* {:panel :svg}
               [:> svg-panel* {:shape shape
                               :objects objects}]]))
          ;; BLUR PANEL
          :blur
          (let [shapes (->> shapes (filter has-blur?))]
            (when (seq shapes)
              [:> style-box* {:panel :blur}
               [:> blur-panel* {:shapes shapes
                                :objects objects}]]))
          ;; TEXT PANEL
          :text
          (let [shapes (filter has-text? shapes)]
            (when (seq shapes)
              [:> style-box* {:panel :text
                              :shorthand (:text shorthands)}
               [:> text-panel* {:shapes shapes
                                :color-space color-space
                                :resolved-tokens resolved-active-tokens
                                :on-font-shorthand set-shorthands}]]))

          ;; SHADOW PANEL
          :shadow
          (let [shapes (filter has-shadow? shapes)]
            (when (seq shapes)
              [:> style-box* {:panel :shadow
                              :shorthand (:shadow shorthands)}
               [:> shadow-panel* {:shapes shapes
                                  :resolved-tokens resolved-active-tokens
                                  :color-space color-space
                                  :on-shadow-shorthand set-shorthands}]]))

          ;; DEFAULT WIP
          [:> style-box* {:panel panel}
           [:div color-space]])])]))
