;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.scale
  "Right sidebar panel of the scale content mode (K shortcut)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.panel-title :refer [panel-title*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private scale-presets
  [0.5 0.75 1 1.5 2 3 4])

(def ^:private anchors
  [[:left :top] [:center :top] [:right :top]
   [:left :center] [:center :center] [:right :center]
   [:left :bottom] [:center :bottom] [:right :bottom]])

(def ^:private indexed-anchors
  (d/enumerate anchors))

(def ^:private default-anchor
  [:center :center])

(defn- factor->id
  [factor]
  (str (mth/precision factor 2)))

(defn- parse-factor
  "Parse a scale typed by the user, like `2`, `1.5x` or `0,5x`"
  [value]
  (let [factor (-> (str value)
                   (str/trim)
                   (str/rtrim "xX")
                   (str/replace "," ".")
                   (d/parse-double))]
    (when (and (some? factor) (pos? factor))
      factor)))

(defn- anchor-label
  [anchor]
  (case anchor
    [:left :top]       (tr "workspace.scale.anchor.top-left")
    [:center :top]     (tr "workspace.scale.anchor.top-center")
    [:right :top]      (tr "workspace.scale.anchor.top-right")
    [:left :center]    (tr "workspace.scale.anchor.center-left")
    [:center :center]  (tr "workspace.scale.anchor.center-center")
    [:right :center]   (tr "workspace.scale.anchor.center-right")
    [:left :bottom]    (tr "workspace.scale.anchor.bottom-left")
    [:center :bottom]  (tr "workspace.scale.anchor.bottom-center")
    [:right :bottom]   (tr "workspace.scale.anchor.bottom-right")))

(mf/defc anchor-point-selector*
  {::mf/private true}
  [{:keys [value disabled on-change aria-labelledby]}]
  (let [on-click
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [index (-> (dom/get-current-target event)
                           (dom/get-data "index")
                           (d/parse-integer))]
             (on-change (nth anchors index)))))]

    [:div {:class (stl/css :anchor-grid)
           :role "radiogroup"
           :aria-labelledby aria-labelledby}
     (for [[index anchor] indexed-anchors]
       (let [selected? (= anchor value)]
         [:button {:key index
                   :type "button"
                   :role "radio"
                   :class (stl/css-case :anchor-point true
                                        :center-area (= anchor default-anchor)
                                        :selected selected?)
                   :aria-checked selected?
                   :aria-label (anchor-label anchor)
                   :disabled disabled
                   :data-index index
                   :on-click on-click}
          [:span {:class (stl/css :anchor-mark)}]]))]))

(mf/defc scale-sidebar*
  []
  (let [selected      (mf/deref refs/selected-shapes)
        shapes-ref    (mf/with-memo [selected]
                        (refs/objects-by-id selected))
        shapes        (mf/deref shapes-ref)

        rect          (mf/with-memo [shapes]
                        (when (seq shapes)
                          (gsh/shapes->rect shapes)))

        width         (get rect :width)
        height        (get rect :height)
        size          (when rect (max width height))
        disabled      (or (nil? size) (mth/almost-zero? size))

        ;; The scale factor is relative to the size of the selection
        ;; when it was selected, so it is only reset when the
        ;; selection changes.
        base-size     (mf/with-memo [selected]
                        size)

        factor        (if (and (not disabled) (some? base-size) (pos? base-size))
                        (/ size base-size)
                        1)

        factor-id     (factor->id factor)

        anchor*       (mf/use-state default-anchor)
        anchor        (deref anchor*)

        width-id      (mf/use-id)
        height-id     (mf/use-id)
        scale-id      (mf/use-id)
        anchor-id     (mf/use-id)

        scale-options
        (mf/with-memo []
          (mapv (fn [factor]
                  {:id (factor->id factor)
                   :label (str (factor->id factor) "x")})
                scale-presets))

        default-selected
        (if (some #(= factor-id (:id %)) scale-options)
          factor-id
          (str factor-id "x"))

        scale-to
        (mf/use-fn
         (mf/deps size anchor)
         (fn [new-size]
           (when (and (some? size) (pos? size) (pos? new-size))
             (st/emit! (dw/scale-selected (/ new-size size) anchor)))))

        on-width-change
        (mf/use-fn
         (mf/deps scale-to width size)
         (fn [value]
           (when (and (number? value) (pos? width))
             (scale-to (* size (/ value width))))))

        on-height-change
        (mf/use-fn
         (mf/deps scale-to height size)
         (fn [value]
           (when (and (number? value) (pos? height))
             (scale-to (* size (/ value height))))))

        on-scale-change
        (mf/use-fn
         (mf/deps scale-to base-size factor-id)
         (fn [value]
           ;; The combobox also reports its value on blur, skip it when
           ;; it is the same rounded factor we are already showing.
           (when-let [factor (parse-factor value)]
             (when (not= factor-id (factor->id factor))
               (scale-to (* base-size factor))))))

        on-anchor-change
        (mf/use-fn
         #(reset! anchor* %))

        on-close
        (mf/use-fn
         #(st/emit! (dw/remove-layout-flag :scale-text)))]

    [:div {:class (stl/css :scale-sidebar)}
     [:> panel-title* {:class (stl/css :scale-title)
                       :text (tr "workspace.scale.title")
                       :on-close on-close}]

     [:div {:class (stl/css :scale-content)}
      [:div {:class (stl/css :field)}
       [:> label* {:for width-id} (tr "workspace.options.width")]
       [:> numeric-input* {:id width-id
                           :icon i/character-w
                           :min 0.01
                           :disabled disabled
                           :placeholder "--"
                           :value width
                           :on-change on-width-change}]]

      [:div {:class (stl/css :field)}
       [:> label* {:for height-id} (tr "workspace.options.height")]
       [:> numeric-input* {:id height-id
                           :icon i/character-h
                           :min 0.01
                           :disabled disabled
                           :placeholder "--"
                           :value height
                           :on-change on-height-change}]]

      [:div {:class (stl/css :field)}
       [:> label* {:for scale-id} (tr "workspace.scale.scale")]
       [:> combobox* {:id scale-id
                      :icon i/scale
                      :disabled disabled
                      :options scale-options
                      :default-selected default-selected
                      :on-change on-scale-change}]]

      [:div {:class (stl/css :field)}
       [:span {:id anchor-id
               :class (stl/css :anchor-title)}
        (tr "workspace.scale.anchor-point")]
       [:> anchor-point-selector* {:value anchor
                                   :disabled disabled
                                   :aria-labelledby anchor-id
                                   :on-change on-anchor-change}]]]]))
