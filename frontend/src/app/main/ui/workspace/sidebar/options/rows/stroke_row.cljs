;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.stroke-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.color :as ctc]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.reorder-handler :refer [reorder-handler*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.common :as soc]
   [app.main.ui.workspace.sidebar.options.menus.input-wrapper-tokens :refer [numeric-input-wrapper*]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc stroke-row*
  [{:keys [index
           stroke
           title
           show-caps
           on-color-change
           on-reorder
           on-color-detach
           on-remove
           on-stroke-width-change
           on-stroke-dash-change
           on-stroke-gap-change
           on-stroke-style-change
           on-stroke-alignment-change
           on-stroke-cap-start-change
           on-stroke-cap-end-change
           on-stroke-cap-switch
           on-toggle-visibility
           disable-drag
           on-focus
           on-blur
           applied-tokens
           on-detach-token
           disable-stroke-style
           select-on-focus
           ids]}]

  (let [hidden?            (:hidden stroke)
        hidden?            (if (nil? hidden?) false hidden?)

        token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        on-drop
        (mf/use-fn
         (mf/deps on-reorder index)
         (fn [relative-pos data]
           (let [from-pos             (:index data)
                 to-space-between-pos (if (= relative-pos :bot) (inc index) index)]
             (on-reorder from-pos to-space-between-pos))))

        [dprops dref]
        (if (some? on-reorder)
          (h/use-sortable
           :data-type "penpot/stroke-row"
           :on-drop on-drop
           :disabled @disable-drag
           :detect-center? false
           :data {:index index})
          [nil nil])

        stroke-color-token
        (:stroke-color applied-tokens)

        on-color-change-refactor
        (mf/use-fn
         (mf/deps index on-color-change)
         (fn [color]
           (on-color-change index color)))

        on-color-detach
        (mf/use-fn
         (mf/deps index on-color-detach)
         (fn [_ color]
           (on-color-detach index color)))

        on-remove
        (mf/use-fn
         (mf/deps index on-remove)
         #(on-remove index))

        stroke-width (:stroke-width stroke)

        on-width-change
        (mf/use-fn
         (mf/deps index on-stroke-width-change ids)
         (fn [value]
           (soc/emit-value-or-token
            value
            #(on-stroke-width-change index %)
            ids
            #{:stroke-width})))

        stroke-dash (:stroke-dash stroke)
        stroke-gap  (:stroke-gap stroke)

        ;; The SVG renderer defaults dash and gap to `stroke-width + 10` when
        ;; unset. Showing that value as placeholder makes the override obvious.
        default-dash-gap (when (number? stroke-width) (+ 10 stroke-width))

        on-dash-change
        (mf/use-fn
         (mf/deps index on-stroke-dash-change)
         #(on-stroke-dash-change index %))

        on-gap-change
        (mf/use-fn
         (mf/deps index on-stroke-gap-change)
         #(on-stroke-gap-change index %))

        stroke-alignment (or (:stroke-alignment stroke) :center)

        stroke-alignment-options
        (mf/with-memo [stroke-alignment]
          (d/concat-vec
           (when (= :multiple stroke-alignment)
             [{:value :multiple :label "--"}])
           [{:value :center :label (tr "workspace.options.stroke.center") :id "center" :icon "stroke-center"}
            {:value :inner :label (tr "workspace.options.stroke.inner") :id "inner" :icon "stroke-inside"}
            {:value :outer :label (tr "workspace.options.stroke.outer") :id "outer" :icon "stroke-outside"}]))

        on-alignment-change
        (mf/use-fn
         (mf/deps index on-stroke-alignment-change)
         #(on-stroke-alignment-change index (keyword %)))

        on-token-change
        (mf/use-fn
         (mf/deps ids)
         (fn [_ token]
           (st/emit!
            (dwta/apply-token-from-input {:token token
                                          :attrs #{:stroke-color}
                                          :shape-ids ids
                                          :expand-with-children true}))))

        stroke-style (or (:stroke-style stroke) :solid)

        stroke-style-options
        (mf/with-memo [stroke-style]
          (d/concat-vec
           (when (= :multiple stroke-style)
             [{:value :multiple :label "--"}])
           [{:value :solid :label (tr "workspace.options.stroke.solid") :id "solid" :icon "stroke-solid"}
            {:value :dotted :label (tr "workspace.options.stroke.dotted") :id "dotted" :icon "stroke-dotted"}
            {:value :dashed :label (tr "workspace.options.stroke.dashed") :id "dashed" :icon "stroke-dashed"}
            {:value :mixed :label (tr "workspace.options.stroke.mixed") :id "mixed" :icon "stroke-mixed"}]))

        on-style-change
        (mf/use-fn
         (mf/deps index on-stroke-style-change)
         #(on-stroke-style-change index (keyword %)))

        on-caps-start-change
        (mf/use-fn
         (mf/deps index on-stroke-cap-start-change)
         #(on-stroke-cap-start-change index (keyword %)))

        on-caps-end-change
        (mf/use-fn
         (mf/deps index on-stroke-cap-end-change)
         #(on-stroke-cap-end-change index (keyword %)))

        on-detach-token-color
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token #{:stroke-color})))

        on-detach-token-width
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token #{:stroke-width})))

        stroke-caps-options
        [{:value nil :label (tr "workspace.options.stroke-cap.none")}
         :separator
         {:value :line-arrow :label (tr "workspace.options.stroke-cap.line-arrow-short") :icon :stroke-arrow}
         {:value :triangle-arrow :label (tr "workspace.options.stroke-cap.triangle-arrow-short") :icon :stroke-triangle}
         {:value :square-marker :label (tr "workspace.options.stroke-cap.square-marker-short") :icon :stroke-rectangle}
         {:value :circle-marker :label (tr "workspace.options.stroke-cap.circle-marker-short") :icon :stroke-circle}
         {:value :diamond-marker :label (tr "workspace.options.stroke-cap.diamond-marker-short") :icon :stroke-diamond}
         :separator
         {:value :round :label (tr "workspace.options.stroke-cap.round") :icon :stroke-rounded}
         {:value :square :label (tr "workspace.options.stroke-cap.square") :icon :stroke-squared}]

        on-cap-switch
        (mf/use-fn
         (mf/deps index on-stroke-cap-switch)
         #(on-stroke-cap-switch index))

        on-toggle-visibility
        (mf/use-fn
         (mf/deps index on-toggle-visibility)
         (fn []
           (when on-toggle-visibility
             (on-toggle-visibility index))))]

    [:div {:class (stl/css-case
                   :stroke-data true
                   :hidden hidden?
                   :dnd-over-top (= (:over dprops) :top)
                   :dnd-over-bot (= (:over dprops) :bot))
           :aria-label (str "stroke-row-" index)}

     (when (some? on-reorder)
       [:> reorder-handler* {:ref dref}])

     ;; Stroke Color
     ;; FIXME: memorize stroke color
     [:div {:class (stl/css :stroke-color-actions)}
      [:> color-row* {:color (ctc/stroke->color stroke)
                      :index index
                      :title title
                      :on-change on-color-change-refactor
                      :on-detach on-color-detach
                      :disable-drag disable-drag
                      :applied-token (if (= index 0)
                                       stroke-color-token
                                       nil)
                      :on-detach-token on-detach-token-color
                      :on-token-change on-token-change
                      :on-focus on-focus
                      :origin :stroke-color
                      :select-on-focus select-on-focus
                      :on-blur on-blur}]

      (when (some? on-toggle-visibility)
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.stroke.toggle-stroke")
                          :on-click on-toggle-visibility
                          :icon (if hidden? "hide" "shown")}])

      [:> icon-button* {:variant "ghost"
                        :aria-label (tr "workspace.options.stroke.remove-stroke")
                        :on-click on-remove
                        :icon i/remove}]]

     ;; Stroke Width, Alignment & Style
     (if token-numeric-inputs
       [:div {:class (stl/css :stroke-options-tokens)}
        [:> numeric-input-wrapper* {:on-change on-width-change
                                    :on-detach on-detach-token-width
                                    :icon i/stroke-size
                                    :min 0
                                    :on-focus on-focus
                                    :on-blur on-blur
                                    :attr :stroke-width
                                    :class (stl/css :numeric-input-wrapper)
                                    :property (tr "workspace.options.stroke-width")
                                    :applied-token (get applied-tokens :stroke-width)
                                    :value stroke-width}]
        [:> select* {:default-selected (d/name stroke-alignment)
                     :options stroke-alignment-options
                     :variant "icon-only"
                     :data-testid "stroke.alignment"
                     :disabled (if (= :multiple hidden?) true hidden?)
                     :wrapper-class (stl/css :stroke-align-icon-select)
                     :on-change on-alignment-change}]

        (when-not disable-stroke-style
          [:> select* {:default-selected (d/name stroke-style)
                       :options stroke-style-options
                       :wrapper-class (stl/css :stroke-style-icon-select)
                       :data-testid "stroke.style"
                       :variant "icon-only"
                       :disabled (if (= :multiple hidden?) true hidden?)
                       :dropdown-alignment :right
                       :on-change on-style-change}])]

       [:div {:class (stl/css :stroke-options)}
        [:div {:class (stl/css :stroke-width-input)
               :title (tr "workspace.options.stroke-width")}
         [:> icon* {:icon-id i/stroke-size
                    :size "s"}]
         [:> deprecated-input/numeric-input* {:value stroke-width
                                              :min 0
                                              :placeholder (tr "settings.multiple")
                                              :on-change on-width-change
                                              :on-focus on-focus
                                              :select-on-focus select-on-focus
                                              :on-blur on-blur}]]
        [:div {:class (stl/css :stroke-alignment-select)
               :data-testid "stroke.alignment"}
         [:& select {:default-value stroke-alignment
                     :options stroke-alignment-options
                     :disabled hidden?
                     :on-change on-alignment-change}]]

        (when-not disable-stroke-style
          [:div {:class (stl/css :stroke-style-select)
                 :data-testid "stroke.style"}
           [:& select {:default-value stroke-style
                       :options stroke-style-options
                       :disabled hidden?
                       :on-change on-style-change}]])])

     ;; Stroke Dash / Gap (only visible for dashed style)
     (when (= stroke-style :dashed)
       [:div {:class (stl/css :stroke-dash-options)
              :data-testid "stroke.dash-options"}
        [:div {:class (stl/css :stroke-dash-input)
               :title (tr "workspace.options.stroke.dash")}
         [:span {:class (stl/css :stroke-dash-label)} (tr "workspace.options.stroke.dash")]
         [:> deprecated-input/numeric-input* {:value stroke-dash
                                              :min 0
                                              :placeholder (if default-dash-gap
                                                             (str default-dash-gap)
                                                             (tr "settings.multiple"))
                                              :on-change on-dash-change
                                              :on-focus on-focus
                                              :select-on-focus select-on-focus
                                              :on-blur on-blur}]]
        [:div {:class (stl/css :stroke-dash-input)
               :title (tr "workspace.options.stroke.gap")}
         [:span {:class (stl/css :stroke-dash-label)} (tr "workspace.options.stroke.gap")]
         [:> deprecated-input/numeric-input* {:value stroke-gap
                                              :min 0
                                              :placeholder (if default-dash-gap
                                                             (str default-dash-gap)
                                                             (tr "settings.multiple"))
                                              :on-change on-gap-change
                                              :on-focus on-focus
                                              :select-on-focus select-on-focus
                                              :on-blur on-blur}]]])

     ;; Stroke Caps
     (when show-caps
       [:div {:class (stl/css :stroke-caps-options)}
        [:& select {:default-value (:stroke-cap-start stroke)
                    :options stroke-caps-options
                    :disabled hidden?
                    :on-change on-caps-start-change}]
        [:> icon-button* {:variant "secondary"
                          :aria-label (tr "labels.switch")
                          :disabled hidden?
                          :on-click on-cap-switch
                          :icon i/switch}]
        [:& select {:default-value (:stroke-cap-end stroke)
                    :options stroke-caps-options
                    :disabled hidden?
                    :on-change on-caps-end-change}]])]))
