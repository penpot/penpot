;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.text
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [uxbox.main.ui.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.workspace.texts :as dwt]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.main.ui.workspace.sidebar.options.measures :refer [measures-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.main.fonts :as fonts]
   [uxbox.util.math :as math]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   ["slate" :refer [Transforms]]))

(def ^:private editor-ref
  (l/derived :editor refs/workspace-local))

(mf/defc font-select-optgroups
  {::mf/wrap [mf/memo]}
  []
  [:*
   [:optgroup {:label "Local"}
    (for [font fonts/local-fonts]
      [:option {:value (:id font)
                :key (:id font)}
       (:name font)])]
   [:optgroup {:label "Google"}
    (for [font (fonts/resolve-fonts :google)]
      [:option {:value (:id font)
                :key (:id font)}
       (:name font)])]])

(mf/defc font-options
  [{:keys [editor shape] :as props}]
  (let [selection (mf/use-ref)

        {:keys [font-id
                font-size
                font-variant-id]
         :or {font-id "sourcesanspro"
              font-size "14"
              font-variant-id "regular"}}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:font-id
                  :font-size
                  :font-variant-id]})

        fonts     (mf/deref fonts/fontsdb)
        font      (get fonts font-id)

        change-font
        (fn [id]
          (st/emit! (dwt/update-text-attrs
                     {:id (:id shape)
                      :editor editor
                      :attrs {:font-id id
                              :font-family (:family (get fonts id))
                              :font-variant-id nil
                              :font-weight nil
                              :font-style nil}})))

        on-font-family-change
        (fn [event]
          (let [id (-> (dom/get-target event)
                       (dom/get-value))
                font (get fonts id)]
            (fonts/ensure-loaded! id (partial change-font id))))

        on-font-size-change
        (fn [event]
          (let [val (-> (dom/get-target event)
                        (dom/get-value))]
            (st/emit! (dwt/update-text-attrs
                       {:id (:id shape)
                        :editor editor
                        :attrs {:font-size val}}))))

        on-font-variant-change
        (fn [event]
          (let [id (-> (dom/get-target event)
                       (dom/get-value))
                variant (d/seek #(= id (:id %)) (:variants font))]

            (st/emit! (dwt/update-text-attrs
                       {:id (:id shape)
                        :editor editor
                        :attrs {:font-id (:id font)
                                :font-family (:family font)
                                :font-variant-id id
                                :font-weight (:weight variant)
                                :font-style (:style variant)}}))))
        ]

    [:*
     [:div.row-flex
      [:select.input-select {:value font-id
                             :on-change on-font-family-change}
       [:& font-select-optgroups]]]

     [:div.row-flex
      [:div.editable-select
       [:select.input-select {:value font-size
                              :on-change on-font-size-change}
        [:option {:value "8"} "8"]
        [:option {:value "9"} "9"]
        [:option {:value "10"} "10"]
        [:option {:value "11"} "11"]
        [:option {:value "12"} "12"]
        [:option {:value "14"} "14"]
        [:option {:value "18"} "18"]
        [:option {:value "24"} "24"]
        [:option {:value "36"} "36"]
        [:option {:value "48"} "48"]
        [:option {:value "72"} "72"]]
       [:input.input-text {:type "number"
                           :min "0"
                           :max "200"
                           :value font-size
                           :on-change on-font-size-change
                           }]]

      [:select.input-select {:value font-variant-id
                             :on-change on-font-variant-change}
       (for [variant (:variants font)]
         [:option {:value (:id variant)
                   :key (pr-str variant)}
          (:name variant)])]]]))


(mf/defc text-align-options
  [{:keys [editor shape locale] :as props}]
  (let [{:keys [text-align]
         :or {text-align "left"}}
        (dwt/current-paragraph-values
         {:editor editor
          :shape shape
          :attrs [:text-align]})

        on-change
        (fn [event type]
          (st/emit! (dwt/update-paragraph-attrs
                     {:id (:id shape)
                      :editor editor
                      :attrs {:text-align type}})))]

    ;; --- Align
    [:div.row-flex.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.font-options.align-left")
       :class (dom/classnames :current (= "left" text-align))
       :on-click #(on-change % "left")}
      i/text-align-left]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.font-options.align-center")
       :class (dom/classnames :current (= "center" text-align))
       :on-click #(on-change % "center")}
      i/text-align-center]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.font-options.align-right")
       :class (dom/classnames :current (= "right" text-align))
       :on-click #(on-change % "right")}
      i/text-align-right]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.font-options.align-justify")
       :class (dom/classnames :current (= "justify" text-align))
       :on-click #(on-change % "justify")}
      i/text-align-justify]]))


(mf/defc text-fill-options
  [{:keys [editor shape] :as props}]
  (let [{:keys [fill opacity]
         :or {fill "#000000"
              opacity 1}}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:fill :opacity]})

        opacity (math/round (* opacity 100))

        on-color-change
        (fn [color]
          (st/emit! (dwt/update-text-attrs
                     {:id (:id shape)
                      :editor editor
                      :attrs {:fill color}})))

        on-color-input-change
        (fn [event]
          (let [input (dom/get-target event)
                value (dom/get-value input)]
            (when (dom/valid? input)
              (on-color-change value))))

        on-opacity-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value))]
            (when (str/numeric? value)
              (let [value (-> (d/parse-integer value 1)
                              (/ 100))]
                (st/emit! (dwt/update-text-attrs
                           {:id (:id shape)
                            :editor editor
                            :attrs {:opacity value}}))))))

        show-color-picker
        (fn [event]
          (let [x (.-clientX event)
                y (.-clientY event)
                props {:x x :y y
                       :on-change on-color-change
                       :default "#ffffff"
                       :value fill
                       :transparent? true}]
            (modal/show! colorpicker-modal props)))]

    [:div.row-flex.color-data
     [:span.color-th
      {:style {:background-color fill}
       :on-click show-color-picker
       }]

     [:div.color-info
      [:input {:default-value fill
               :pattern "^#(?:[0-9a-fA-F]{3}){1,2}$"
               :ref (fn [el]
                      (when el
                        (set! (.-value el) fill)))
               :on-change on-color-input-change
               }]]

     [:div.input-element.percentail
      [:input.input-text {:type "number"
                          :ref (fn [el]
                                 (when el
                                   (set! (.-value el) opacity)))
                          :default-value opacity
                          :on-change on-opacity-change
                          :min "0"
                          :max "100"}]]

     [:input.slidebar {:type "range"
                       :min "0"
                       :max "100"
                       :value opacity
                       :step "1"
                       :on-change on-opacity-change
                       }]]))

(mf/defc spacing-options
  [{:keys [editor shape locale] :as props}]
  (let [{:keys [letter-spacing
                line-height]
         :or {line-height "1.2"
              letter-spacing "0"}}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:line-height
                  :letter-spacing]})

        on-change
        (fn [event attr]
          (let [val (-> (dom/get-target event)
                        (dom/get-value))]
            (st/emit! (dwt/update-text-attrs
                       {:id (:id shape)
                        :editor editor
                        :attrs {attr val}}))))]
    [:div.row-flex
     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.line-height")}
       i/line-height]
      [:input.input-text
       {:type "number"
        :step "0.1"
        :min "0"
        :max "200"
        :value line-height
        :on-change #(on-change % :line-height)}]]

     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.letter-spacing")}
       i/letter-spacing]
      [:input.input-text
       {:type "number"
        :step "0.1"
        :min "0"
        :max "200"
        :value letter-spacing
        :on-change #(on-change % :letter-spacing)}]]]))

;; (mf/defc box-sizing-options
;;   [{:keys [editor] :as props}]
;;   [:div.align-icons
;;    [:span.tooltip.tooltip-bottom
;;     {:alt "Auto height"}
;;     i/auto-height]
;;    [:span.tooltip.tooltip-bottom
;;     {:alt "Auto width"}
;;     i/auto-width]
;;    [:span.tooltip.tooltip-bottom
;;     {:alt "Fixed size"}
;;     i/auto-fix]])

(mf/defc vertical-align-options
  [{:keys [editor locale shape] :as props}]
  (let [{:keys [vertical-align]
         :or {vertical-align "top"}}
        (dwt/current-root-values
         {:editor editor
          :shape shape
          :attrs [:vertical-align]})

        on-change
        (fn [event type]
          (st/emit! (dwt/update-root-attrs
                     {:id (:id shape)
                      :editor editor
                      :attrs {:vertical-align type}})))]

    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.font-options.vertical-align")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.align-top")
        :class (dom/classnames :current (= "top" vertical-align))
        :on-click #(on-change % "top")}
       i/align-top]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.align-middle")
        :class (dom/classnames :current (= "center" vertical-align))
        :on-click #(on-change % "center")}
       i/align-middle]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.align-bottom")
        :class (dom/classnames :current (= "bottom" vertical-align))
        :on-click #(on-change % "bottom")}
       i/align-bottom]]]))

(mf/defc text-decoration-options
  [{:keys [editor locale shape] :as props}]
  (let [{:keys [text-decoration]
         :or {text-decoration "none"}}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:text-decoration]})

        on-change
        (fn [event type]
          (st/emit! (dwt/update-text-attrs
                     {:id (:id shape)
                      :editor editor
                      :attrs {:text-decoration type}})))]
    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.font-options.decoration")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.none")
        :class (dom/classnames :current (= "none" text-decoration))
        :on-click #(on-change % "none")}
       i/minus]

      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.underline")
        :class (dom/classnames :current (= "underline" text-decoration))
        :on-click #(on-change % "underline")}
       i/underline]

      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.strikethrough")
        :class (dom/classnames :current (= "line-through" text-decoration))
        :on-click #(on-change % "line-through")}
       i/strikethrough]]]))

(mf/defc text-transform-options
  [{:keys [editor locale shape] :as props}]
  (let [{:keys [text-transform]
         :or {text-transform "none"}}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:text-transform]})

        on-change
        (fn [event type]
          (st/emit! (dwt/update-text-attrs
                     {:id (:id shape)
                      :editor editor
                      :attrs {:text-transform type}})))]
    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.font-options.text-case")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.none")
        :class (dom/classnames :current (= "none" text-transform))
        :on-click #(on-change % "none")}
       i/minus]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.uppercase")
        :class (dom/classnames :current (= "uppercase" text-transform))
        :on-click #(on-change % "uppercase")}
       i/uppercase]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.lowercase")
        :class (dom/classnames :current (= "lowercase" text-transform))
        :on-click #(on-change % "lowercase")}
       i/lowercase]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.font-options.titlecase")
        :class (dom/classnames :current (= "capitalize" text-transform))
        :on-click #(on-change % "capitalize")}
       i/titlecase]]]))

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]
  (let [id (:id shape)
        local (mf/deref refs/workspace-local)
        editor (get-in local [:editors (:id shape)])
        locale (mf/deref i18n/locale)]
    [:*
     [:div.element-set
      [:div.element-set-title (t locale "workspace.options.fill")]
      [:div.element-set-content
       [:& text-fill-options {:editor editor :shape shape}]]]


     [:div.element-set
      [:div.element-set-title (t locale "workspace.options.font-options")]
      [:div.element-set-content
       [:& font-options {:editor editor :locale locale :shape shape}]
       [:& text-align-options {:editor editor :locale locale :shape shape}]
       [:& spacing-options {:editor editor :locale locale :shape shape}]
       [:& vertical-align-options {:editor editor :locale locale :shape shape}]
       [:& text-decoration-options {:editor editor :locale locale :shape shape}]
       [:& text-transform-options {:editor editor :locale locale :shape shape}]]]]))

(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures-menu {:shape shape}]
   [:& text-menu {:shape shape}]])
