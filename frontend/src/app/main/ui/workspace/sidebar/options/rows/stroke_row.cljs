;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.stroke-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc stroke-row
  {::mf/wrap-props false}
  [{:keys [index
           stroke
           title
           show-caps
           on-color-change
           on-reorder
           on-color-detach
           on-remove
           on-stroke-width-change
           on-stroke-style-change
           on-stroke-alignment-change
           on-stroke-cap-start-change
           on-stroke-cap-end-change
           on-stroke-cap-switch
           disable-drag
           on-focus
           on-blur
           disable-stroke-style
           select-on-focus]}]

  (let [on-drop
        (fn [_ data]
          (on-reorder (:index data)))

        [dprops dref] (if (some? on-reorder)
                        (h/use-sortable
                         :data-type "penpot/stroke-row"
                         :on-drop on-drop
                         :disabled @disable-drag
                         :detect-center? false
                         :data {:id (str "stroke-row-" index)
                                :index index
                                :name (str "Border row" index)})
                        [nil nil])

        on-color-change-refactor
        (mf/use-callback
         (mf/deps index on-color-change)
         (fn [color]
           (on-color-change index color)))

        on-color-detach
        (mf/use-callback
         (mf/deps index on-color-detach)
         (fn [color]
           (on-color-detach index color)))

        on-remove
        (mf/use-callback
         (mf/deps index on-remove)
         #(on-remove index))

        stroke-width (:stroke-width stroke)

        on-width-change
        (mf/use-callback
         (mf/deps index on-stroke-width-change)
         #(on-stroke-width-change index %))

        stroke-alignment (or (:stroke-alignment stroke) :center)

        stroke-alignment-options
        (mf/with-memo [stroke-alignment]
          (d/concat-vec
           (when (= :multiple stroke-alignment)
             [{:value :multiple :label "--"}])
           [{:value :center :label (tr "workspace.options.stroke.center")}
            {:value :inner :label (tr "workspace.options.stroke.inner")}
            {:value :outer :label (tr "workspace.options.stroke.outer")}]))

        on-alignment-change
        (mf/use-callback
         (mf/deps index on-stroke-alignment-change)
         #(on-stroke-alignment-change index (keyword %)))

        stroke-style (or (:stroke-style stroke) :solid)

        stroke-style-options
        (mf/with-memo [stroke-style]
          (d/concat-vec
           (when (= :multiple stroke-style)
             [{:value :multiple :label "--"}])
           [{:value :solid :label (tr "workspace.options.stroke.solid")}
            {:value :dotted :label (tr "workspace.options.stroke.dotted")}
            {:value :dashed :label (tr "workspace.options.stroke.dashed")}
            {:value :mixed :label (tr "workspace.options.stroke.mixed")}]))

        on-style-change
        (mf/use-callback
         (mf/deps index on-stroke-style-change)
         #(on-stroke-style-change index (keyword %)))

        on-caps-start-change
        (mf/use-callback
         (mf/deps index on-stroke-cap-start-change)
         #(on-stroke-cap-start-change index (keyword %)))

        on-caps-end-change
        (mf/use-callback
         (mf/deps index on-stroke-cap-end-change)
         #(on-stroke-cap-end-change index (keyword %)))

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
        (mf/use-callback
         (mf/deps index on-stroke-cap-switch)
         #(on-stroke-cap-switch index))]

    [:div {:class (stl/css-case
                   :stroke-data true
                   :dnd-over-top (= (:over dprops) :top)
                   :dnd-over-bot (= (:over dprops) :bot))
           :ref dref}
           ;; Stroke Color
     [:& color-row {:color {:color (:stroke-color stroke)
                            :opacity (:stroke-opacity stroke)
                            :id (:stroke-color-ref-id stroke)
                            :file-id (:stroke-color-ref-file stroke)
                            :gradient (:stroke-color-gradient stroke)
                            :image (:stroke-image stroke)}
                    :index index
                    :title title
                    :on-change on-color-change-refactor
                    :on-detach on-color-detach
                    :on-remove on-remove
                    :disable-drag disable-drag
                    :on-focus on-focus
                    :select-on-focus select-on-focus
                    :on-blur on-blur}]

           ;; Stroke Width, Alignment & Style
     [:div {:class (stl/css :stroke-options)}
      [:div {:class (stl/css :stroke-width-input-element)
             :title (tr "workspace.options.stroke-width")}
       [:span {:class (stl/css :icon)}
        i/stroke-size]
       [:> numeric-input*
        {:min 0
         :className (stl/css :stroke-width-input)
         :value stroke-width
         :placeholder (tr "settings.multiple")
         :on-change on-width-change
         :on-focus on-focus
         :select-on-focus select-on-focus
         :on-blur on-blur}]]

      [:div {:class (stl/css :select-wrapper)
             :data-testid "stroke.alignment"}
       [:& select
        {:default-value stroke-alignment
         :options stroke-alignment-options
         :on-change on-alignment-change}]]

      (when-not disable-stroke-style
        [:div {:class (stl/css :select-wrapper)
               :data-testid "stroke.style"}
         [:& select
          {:default-value stroke-style
           :options stroke-style-options
           :on-change on-style-change}]])]

           ;; Stroke Caps
     (when show-caps
       [:div {:class (stl/css :stroke-caps-options)}
        [:div {:class (stl/css :cap-select)}
         [:& select
          {:default-value (:stroke-cap-start stroke)
           :dropdown-class (stl/css :stroke-cap-dropdown-start)
           :options stroke-caps-options
           :on-change on-caps-start-change}]]

        [:button {:class (stl/css :swap-caps-btn)
                  :on-click on-cap-switch}
         i/switch]

        [:div {:class (stl/css :cap-select)}
         [:& select
          {:default-value (:stroke-cap-end stroke)
           :dropdown-class (stl/css :stroke-cap-dropdown)
           :options stroke-caps-options
           :on-change on-caps-end-change}]]])]))
