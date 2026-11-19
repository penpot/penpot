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
   [app.common.types.component :as ctk]
   [app.main.data.workspace :as dw]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.panel-title :refer [panel-title*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.formats :as fmt]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private scale-presets
  [0.5 0.75 1 1.5 2 3 4])

(def ^:private anchors
  [{:value [:left :top]
    :class (stl/css :anchor-top-left)
    :label #(tr "workspace.scale.anchor.top-left")}
   {:value [:center :top]
    :class (stl/css :anchor-top-center)
    :label #(tr "workspace.scale.anchor.top-center")}
   {:value [:right :top]
    :class (stl/css :anchor-top-right)
    :label #(tr "workspace.scale.anchor.top-right")}
   {:value [:left :center]
    :class (stl/css :anchor-center-left)
    :label #(tr "workspace.scale.anchor.center-left")}
   {:value [:center :center]
    :class (stl/css :anchor-center)
    :label #(tr "workspace.scale.anchor.center")}
   {:value [:right :center]
    :class (stl/css :anchor-center-right)
    :label #(tr "workspace.scale.anchor.center-right")}
   {:value [:left :bottom]
    :class (stl/css :anchor-bottom-left)
    :label #(tr "workspace.scale.anchor.bottom-left")}
   {:value [:center :bottom]
    :class (stl/css :anchor-bottom-center)
    :label #(tr "workspace.scale.anchor.bottom-center")}
   {:value [:right :bottom]
    :class (stl/css :anchor-bottom-right)
    :label #(tr "workspace.scale.anchor.bottom-right")}])

(def ^:private default-anchor
  [:center :center])

(def ^:private factor-pattern
  #"^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)$")

(defn- factor->string
  [factor]
  (let [precision (cond
                    (< factor 0.01) 4
                    (< factor 0.1)  3
                    :else           2)]
    (or (fmt/format-number factor {:precision precision})
        "1")))

(defn- parse-factor
  "Parses a scale typed by the user, like `2`, `1.5x` or `0,5x`.
  Returns nil when the value is not a positive number, optionally
  followed by an `x`."
  [value]
  (let [text (-> (str value)
                 (str/trim)
                 (str/lower)
                 (str/replace #"x$" "")
                 (str/trim)
                 (str/replace "," "."))]
    (when (re-matches factor-pattern text)
      (let [factor (d/parse-double text)]
        (when (and (mth/finite? factor) (pos? factor))
          factor)))))

(defn scale-state-for-selection
  "Starts a scale session at 1x for the selected IDs."
  [selected]
  {:selection selected :factor 1})

(defn selection-scale-factor
  "Returns 1x when the stored scale belongs to a different selection."
  [scale-state selected]
  (if (= selected (:selection scale-state))
    (:factor scale-state)
    1))

(defn selection-anchor
  "Returns the saved anchor, or the center for a new non-empty selection."
  [anchor-state selected]
  (when (seq selected)
    (if (= selected (:selection anchor-state))
      (:anchor anchor-state)
      default-anchor)))

(defn calculate-scale-after-dimension-change
  "Updates the current scale factor by a width or height change ratio."
  [factor dimension new-dimension]
  (* factor (/ new-dimension dimension)))

(defn- apply-preview-modifiers
  [shapes modifiers wasm-modifiers wasm?]
  (mapv (fn [shape]
          (let [id (:id shape)]
            (if wasm?
              (gsh/apply-transform shape (get wasm-modifiers id))
              (gsh/transform-shape shape (get-in modifiers [id :modifiers])))))
        shapes))

(defn- scale-input-operation
  [factor value]
  (when-let [new-factor (parse-factor value)]
    (when-not (mth/close? new-factor factor)
      {:multiplier (/ new-factor factor)
       :factor new-factor})))

(mf/defc anchor-point-selector*
  {::mf/private true}
  [{:keys [value disabled group-name on-change]}]
  [:div {:class (stl/css :anchor-grid)
         :role "radiogroup"
         :aria-label (tr "workspace.scale.anchor-point")}
   (for [{anchor-value :value
          anchor-class :class
          get-label :label} anchors]
     (let [selected? (= anchor-value value)
           label     (get-label)]
       [:> tooltip* {:key (str (first anchor-value) "-" (second anchor-value))
                     :content label
                     :class (str anchor-class " " (stl/css :anchor-point-tooltip))}
        [:label {:class [(stl/css :anchor-point)
                         (stl/css-case :center-area (= anchor-value default-anchor)
                                       :disabled disabled)]}
         [:input {:type "radio"
                  :class (stl/css :anchor-input)
                  :name group-name
                  :checked selected?
                  :disabled disabled
                  :aria-label label
                  :on-change #(on-change anchor-value)}]
         [:span {:class (stl/css :anchor-mark)}]]]))])

(mf/defc scale-sidebar*
  []
  (let [selected   (mf/deref refs/selected-shapes)
        shapes-ref  (mf/with-memo [selected]
                      (refs/objects-by-id selected))
        shapes-data (mf/deref shapes-ref)

        workspace-modifiers (mf/deref refs/workspace-modifiers)
        wasm-modifiers      (mf/deref refs/workspace-wasm-modifiers)
        wasm?               (features/active-feature? @st/state "render-wasm/v1")

        selected-shapes (mf/with-memo [shapes-data]
                          (remove ctk/is-variant-container? shapes-data))

        shapes      (mf/with-memo [selected-shapes workspace-modifiers wasm-modifiers wasm?]
                      (apply-preview-modifiers selected-shapes
                                               workspace-modifiers
                                               (into {} wasm-modifiers)
                                               wasm?))

        rect       (mf/with-memo [shapes]
                     (when (seq shapes)
                       (gsh/shapes->rect shapes)))

        width      (get rect :width)
        height     (get rect :height)
        size       (when rect (max width height))
        disabled   (or (nil? size) (mth/almost-zero? size))

        width-disabled  (or disabled (mth/almost-zero? (d/nilv width 0)))
        height-disabled (or disabled (mth/almost-zero? (d/nilv height 0)))

        scale-state* (mf/use-state (scale-state-for-selection selected))
        scale-state  (deref scale-state*)
        factor       (selection-scale-factor scale-state selected)

        factor-str (factor->string factor)
        factor-id  (str factor-str)

        anchor*    (mf/use-state nil)
        anchor-map (deref anchor*)

        anchor     (selection-anchor anchor-map selected)

        width-id       (mf/use-id)
        height-id      (mf/use-id)
        scale-id       (mf/use-id)
        size-title-id  (mf/use-id)
        scale-title-id (mf/use-id)
        group-id       (mf/use-id)

        revision*  (mf/use-state 0)
        revision   (deref revision*)

        scale-options
        (mf/with-memo []
          (mapv (fn [factor]
                  (let [value (factor->string factor)]
                    {:id value
                     :label (str value "x")}))
                scale-presets))

        default-selected
        (if (some #(= factor-id (:id %)) scale-options)
          factor-id
          (str factor-id "x"))

        reset-scale-input
        (mf/use-fn
         #(swap! revision* inc))

        scale-by
        (mf/use-fn
         (mf/deps size anchor factor selected)
         (fn [multiplier new-factor]
           (when (and (some? size)
                      (pos? size)
                      (pos? multiplier))
             (st/emit! (dw/scale-selected multiplier
                                          (or anchor default-anchor)))
             (reset! scale-state* {:selection selected :factor new-factor}))))

        on-width-change
        (mf/use-fn
         (mf/deps width factor scale-by)
         (fn [value]
           (when (and (number? value) (some? width) (pos? width) (pos? value))
             (scale-by (/ value width)
                       (calculate-scale-after-dimension-change factor width value)))))

        on-height-change
        (mf/use-fn
         (mf/deps height factor scale-by)
         (fn [value]
           (when (and (number? value) (some? height) (pos? height) (pos? value))
             (scale-by (/ value height)
                       (calculate-scale-after-dimension-change factor height value)))))

        on-scale-change
        (mf/use-fn
         (mf/deps factor scale-by reset-scale-input)
         (fn [value]
           (if-let [{:keys [multiplier factor]} (scale-input-operation factor value)]
             (scale-by multiplier factor)
             ;; Normalize the display without scaling for invalid or unchanged input.
             (reset-scale-input))))

        on-anchor-change
        (mf/use-fn
         (mf/deps selected)
         (fn [anchor]
           (reset! anchor* {:selection selected :anchor anchor})))

        on-close
        (mf/use-fn
         #(st/emit! (dw/remove-layout-flag :scale-text)))]

    (mf/with-layout-effect [selected]
      (when-not (= selected (:selection (deref scale-state*)))
        (reset! scale-state* (scale-state-for-selection selected))
        (reset! anchor* nil)))

    [:div {:class (stl/css :scale-sidebar)}
     [:> panel-title* {:class (stl/css :scale-title)
                       :text (tr "workspace.scale.title")
                       :on-close on-close}]

     [:div {:class (stl/css :scale-content)}
      [:section {:class (stl/css :scale-row)
                 :aria-labelledby size-title-id}
       [:div {:id size-title-id}
        [:> title-bar* {:collapsable false
                        :title (tr "workspace.options.size")}]]
       [:div {:class (stl/css :scale-fields)}
        [:div {:class (stl/css :scale-field)}
         [:> numeric-input* {:id width-id
                             :icon i/character-w
                             :property (tr "workspace.options.width")
                             :min 0.01
                             :disabled width-disabled
                             :placeholder "--"
                             :value width
                             :on-change on-width-change}]]
        [:div {:class (stl/css :scale-field)}
         [:> numeric-input* {:id height-id
                             :icon i/character-h
                             :property (tr "workspace.options.height")
                             :min 0.01
                             :disabled height-disabled
                             :placeholder "--"
                             :value height
                             :on-change on-height-change}]]]]

      [:section {:class (stl/css :scale-row)
                 :aria-labelledby scale-title-id}
       [:div {:id scale-title-id}
        [:> title-bar* {:collapsable false
                        :title (tr "workspace.scale.scale-and-anchor")}]]
       [:div {:class (stl/css :scale-fields)}
        [:div {:class (stl/css :scale-field)}
         [:> combobox* {:key (str revision "-" factor-id)
                        :id scale-id
                        :icon i/scale
                        :disabled disabled
                        :options scale-options
                        :default-selected default-selected
                        :on-change on-scale-change}]]
        [:div {:class (stl/css :scale-field)}
         [:> anchor-point-selector* {:value anchor
                                     :disabled disabled
                                     :group-name (str "scale-anchor-" group-id)
                                     :on-change on-anchor-change}]]]]]]))
