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
   [uxbox.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [uxbox.util.dom :as dom]
   [uxbox.main.fonts :as fonts]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   ["slate" :refer [Transforms]]))

(defn- attr->string [value]
  (if (= value :multiple)
    ""
    (str value)))

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
  [{:keys [editor shape locale] :as props}]
  (let [selection (mf/use-ref)

        {:keys [font-id
                font-size
                font-variant-id]}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:font-id
                  :font-size
                  :font-variant-id]})

        font-id (or font-id "sourcesanspro")
        font-size (or font-size "14")
        font-variant-id (or font-variant-id "regular")

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
                       (dom/get-value))]
            (when-not (str/empty? id)
              (let [font (get fonts id)]
                (fonts/ensure-loaded! id (partial change-font id))))))

        on-font-size-change
        (fn [event]
          (let [val (-> (dom/get-target event)
                        (dom/get-value))]
            (when-not (str/empty? val)
              (st/emit! (dwt/update-text-attrs
                         {:id (:id shape)
                          :editor editor
                          :attrs {:font-size val}})))))

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
                                :font-style (:style variant)}}))))]

    [:*
     [:div.row-flex
      [:select.input-select {:value (attr->string font-id)
                             :on-change on-font-family-change}
       (when (= font-id :multiple)
         [:option {:value ""} (t locale "settings.multiple")])
       [:& font-select-optgroups]]]

     [:div.row-flex
      [:div.editable-select
       [:select.input-select {:value (attr->string font-size)
                              :on-change on-font-size-change}
        (when (= font-size :multiple)
          [:option {:value ""} "--"])
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
                           :placeholder "--"
                           :on-change on-font-size-change}]]

      [:select.input-select {:value (attr->string font-variant-id)
                             :on-change on-font-variant-change}
       (when (= font-size :multiple)
         [:option {:value ""} "--"])
       (for [variant (:variants font)]
         [:option {:value (:id variant)
                   :key (pr-str variant)}
          (:name variant)])]]]))


(mf/defc text-align-options
  [{:keys [editor shape locale] :as props}]
  (let [{:keys [text-align]}
        (dwt/current-paragraph-values
         {:editor editor
          :shape shape
          :attrs [:text-align]})

        text-align (or text-align "left")

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
  (let [text-color (dwt/current-text-values
                    {:editor editor
                     :shape shape
                     :attrs [:fill :opacity]})

        current-color {:value (:fill text-color)
                       :opacity (:opacity text-color)}

        handle-change-color
        (fn [value opacity]
          (st/emit! (dwt/update-text-attrs {:id (:id shape)
                                            :editor editor
                                            :attrs {:fill value
                                                    :opacity opacity}})))]

    [:& color-row {:color current-color
                   :on-change handle-change-color}]))

(mf/defc spacing-options
  [{:keys [editor shape locale] :as props}]
  (let [{:keys [line-height
                letter-spacing]}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:line-height
                  :letter-spacing]})

        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")

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
        :value (attr->string line-height)
        :placeholder (t locale "settings.multiple")
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
        :value (attr->string letter-spacing)
        :placeholder (t locale "settings.multiple")
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
  (let [{:keys [vertical-align]}
        (dwt/current-root-values
         {:editor editor
          :shape shape
          :attrs [:vertical-align]})

        vertical-align (or vertical-align "top")

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
  (let [{:keys [text-decoration]}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:text-decoration]})

        text-decoration (or text-decoration "none")

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
  (let [{:keys [text-transform]}
        (dwt/current-text-values
         {:editor editor
          :shape shape
          :attrs [:text-transform]})

        text-transform (or text-transform "none")

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
  (let [ids [(:id shape)]
        type (:type shape)
        measure-values (select-keys shape measure-attrs)]
    [:div
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values}]
     [:& text-menu {:shape shape}]]))
