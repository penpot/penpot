;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.stroke-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- width->string [width]
  (if (= width :multiple)
   ""
   (str (or width 1))))

(defn- enum->string [value]
  (if (= value :multiple)
    ""
    (pr-str value)))

(defn- stroke-cap-names []
  [[nil             (tr "workspace.options.stroke-cap.none")           false]
   [:line-arrow     (tr "workspace.options.stroke-cap.line-arrow")     true]
   [:triangle-arrow (tr "workspace.options.stroke-cap.triangle-arrow") false]
   [:square-marker  (tr "workspace.options.stroke-cap.square-marker")  false]
   [:circle-marker  (tr "workspace.options.stroke-cap.circle-marker")  false]
   [:diamond-marker (tr "workspace.options.stroke-cap.diamond-marker") false]
   [:round          (tr "workspace.options.stroke-cap.round")          true]
   [:square         (tr "workspace.options.stroke-cap.square")         false]])

(defn- value->img [value]
  (when (and value (not= value :multiple))
    (str "images/cap-" (name value) ".svg")))

(defn- value->name [value]
  (if (= value :multiple)
    "--"
    (-> (d/seek #(= (first %) value) (stroke-cap-names))
        (second))))

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
           open-caps-select
           close-caps-select
           on-stroke-cap-start-change
           on-stroke-cap-end-change
           on-stroke-cap-switch
           disable-drag
           on-focus
           on-blur
           disable-stroke-style
           select-on-focus]}]
  (let [new-css-system    (mf/use-ctx ctx/new-css-system)
        start-caps-state* (mf/use-state {:open? false
                                         :top 0
                                         :left 0})

        start-caps-state  (deref start-caps-state*)

        end-caps-state   (mf/use-state {:open? false
                                        :top 0
                                        :left 0})
        on-drop
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

        on-color-detach-refactor
        (mf/use-callback
         (mf/deps index on-color-detach)
         (fn [color]
           (on-color-detach index color)))

        on-remove-refactor
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
         {:value :line-arrow :label (tr "workspace.options.stroke-cap.line-arrow-short")}
         {:value :triangle-arrow :label (tr "workspace.options.stroke-cap.triangle-arrow-short")}
         {:value :square-marker :label (tr "workspace.options.stroke-cap.square-marker-short")}
         {:value :circle-marker :label (tr "workspace.options.stroke-cap.circle-marker-short")}
         {:value :diamond-marker :label (tr "workspace.options.stroke-cap.diamond-marker-short")}
         :separator
         {:value :round :label (tr "workspace.options.stroke-cap.round")}
         {:value :square :label (tr "workspace.options.stroke-cap.square")}]

        on-cap-switch
        (mf/use-callback
         (mf/deps index on-stroke-cap-switch)
         #(on-stroke-cap-switch index))]

    (if new-css-system
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
                      :on-detach on-color-detach-refactor
                      :on-remove on-remove-refactor
                      :disable-drag disable-drag
                      :on-focus on-focus
                      :select-on-focus select-on-focus
                      :on-blur on-blur}]

       ;; Stroke Width, Alignment & Style
       [:div {:class (stl/css :stroke-options)}
        [:div {:class (stl/css :stroke-width-input-element)
               :title (tr "workspace.options.stroke-width")}
         [:span {:class (stl/css :icon)}
          i/stroke-size-refactor]
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
               :data-test "stroke.alignment"}
         [:& select
          {:default-value stroke-alignment
           :options stroke-alignment-options
           :on-change on-alignment-change}]]

        (when-not disable-stroke-style
          [:div {:class (stl/css :select-wrapper)
                 :data-test "stroke.style"}
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
             :options stroke-caps-options
             :on-change on-caps-start-change}]]

          [:button {:class (stl/css :swap-caps-btn)
                    :on-click on-cap-switch}
           i/switch-refactor]

          [:div {:class (stl/css :cap-select)}
           [:& select
            {:default-value (:stroke-cap-end stroke)
             :options stroke-caps-options
             :on-change on-caps-end-change}]]])]



      [:div.border-data {:class (dom/classnames
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
                      :on-change (on-color-change index)
                      :on-detach (on-color-detach index)
                      :on-remove (on-remove index)
                      :disable-drag disable-drag
                      :on-focus on-focus
                      :select-on-focus select-on-focus
                      :on-blur on-blur}]

           ;; Stroke Width, Alignment & Style
       [:div.row-flex
        [:div.input-element
         {:class (dom/classnames :pixels (not= (:stroke-width stroke) :multiple))
          :title (tr "workspace.options.stroke-width")}

         [:> numeric-input*
          {:min 0
           :value (-> (:stroke-width stroke) width->string)
           :placeholder (tr "settings.multiple")
           :on-change (on-stroke-width-change index)
           :on-focus on-focus
           :select-on-focus select-on-focus
           :on-blur on-blur}]]

        [:select#style.input-select {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
                                     :value (enum->string (:stroke-alignment stroke))
                                     :on-change (on-stroke-alignment-change index)}
         (when (= (:stroke-alignment stroke) :multiple)
           [:option {:value ""} "--"])
         [:option {:value ":center"} (tr "workspace.options.stroke.center")]
         [:option {:value ":inner"} (tr "workspace.options.stroke.inner")]
         [:option {:value ":outer"} (tr "workspace.options.stroke.outer")]]

        (when-not disable-stroke-style
          [:select#style.input-select {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
                                       :value (enum->string (:stroke-style stroke))
                                       :on-change (on-stroke-style-change index)}
           (when (= (:stroke-style stroke) :multiple)
             [:option {:value ""} "--"])
           [:option {:value ":solid"} (tr "workspace.options.stroke.solid")]
           [:option {:value ":dotted"} (tr "workspace.options.stroke.dotted")]
           [:option {:value ":dashed"} (tr "workspace.options.stroke.dashed")]
           [:option {:value ":mixed"} (tr "workspace.options.stroke.mixed")]])]

           ;; Stroke Caps
       (when show-caps
         [:div.row-flex
          [:div.cap-select {:tab-index 0 ;; tab-index to make the element focusable
                            :on-click (open-caps-select start-caps-state*)}
           (value->name (:stroke-cap-start stroke))
           [:span.cap-select-button
            i/arrow-down]]
          [:& dropdown {:show (:open? start-caps-state)
                        :on-close (close-caps-select start-caps-state*)}
           [:ul.dropdown.cap-select-dropdown {:style {:top  (:top start-caps-state)
                                                      :left (:left start-caps-state)}}
            (for [[idx [value label separator]] (d/enumerate (stroke-cap-names))]
              (let [img (value->img value)]
                [:li {:key (dm/str "start-cap-" idx)
                      :class (dom/classnames :separator separator)
                      :on-click #(on-stroke-cap-start-change index value)}
                 (when img [:img {:src (value->img value)}])
                 label]))]]

          [:div.element-set-actions-button {:on-click #(on-stroke-cap-switch index)}
           i/switch]

          [:div.cap-select {:tab-index 0
                            :on-click (open-caps-select end-caps-state)}
           (value->name (:stroke-cap-end stroke))
           [:span.cap-select-button
            i/arrow-down]]
          [:& dropdown {:show (:open? @end-caps-state)
                        :on-close (close-caps-select end-caps-state)}
           [:ul.dropdown.cap-select-dropdown {:style {:top  (:top @end-caps-state)
                                                      :left (:left @end-caps-state)}}
            (for [[idx [value label separator]] (d/enumerate (stroke-cap-names))]
              (let [img (value->img value)]
                [:li {:key (dm/str "end-cap-" idx)
                      :class (dom/classnames :separator separator)
                      :on-click #(on-stroke-cap-end-change index value)}
                 (when img [:img {:src (value->img value)}])
                 label]))]]])])))
