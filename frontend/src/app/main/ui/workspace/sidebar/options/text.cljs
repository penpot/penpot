;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.text
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.main.ui.icons :as i]
   [app.common.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.util.dom :as dom]
   [app.main.fonts :as fonts]
   [app.util.i18n :as i18n :refer [tr t]]
   ["slate" :refer [Transforms]]))

(def text-fill-attrs [:fill :opacity])
(def text-font-attrs [:font-id :font-family :font-variant-id :font-size :font-weight :font-style])
(def text-align-attrs [:text-align])
(def text-spacing-attrs [:line-height :letter-spacing])
(def text-valign-attrs [:vertical-align])
(def text-decoration-attrs [:text-decoration])
(def text-transform-attrs [:text-transform])

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
  [{:keys [editor ids values locale] :as props}]
  (let [{:keys [font-id
                font-size
                font-variant-id]} values

        font-id (or font-id "sourcesanspro")
        font-size (or font-size "14")
        font-variant-id (or font-variant-id "regular")

        fonts     (mf/deref fonts/fontsdb)
        font      (get fonts font-id)

        change-font
        (fn [new-font-id]
          (run! #(st/emit! (dwt/update-text-attrs
                             {:id %
                              :editor editor
                              :attrs {:font-id new-font-id
                                      :font-family (:family (get fonts new-font-id))
                                      :font-variant-id nil
                                      :font-weight nil
                                      :font-style nil}}))
                ids))

        on-font-family-change
        (fn [event]
          (let [new-font-id (-> (dom/get-target event)
                                (dom/get-value))]
            (when-not (str/empty? new-font-id)
              (let [font (get fonts new-font-id)]
                (fonts/ensure-loaded! new-font-id (partial change-font new-font-id))))))

        on-font-size-change
        (fn [new-font-size]
          (when-not (str/empty? new-font-size)
            (run! #(st/emit! (dwt/update-text-attrs
                              {:id %
                               :editor editor
                               :attrs {:font-size (str new-font-size)}}))
                  ids)))

        on-font-variant-change
        (fn [event]
          (let [new-variant-id (-> (dom/get-target event)
                                   (dom/get-value))
                variant (d/seek #(= new-variant-id (:id %)) (:variants font))]

            (run! #(st/emit! (dwt/update-text-attrs
                               {:id %
                                :editor editor
                                :attrs {:font-id (:id font)
                                        :font-family (:family font)
                                        :font-variant-id new-variant-id
                                        :font-weight (:weight variant)
                                        :font-style (:style variant)}}))
                  ids)))]

    [:*
     [:div.row-flex
      [:select.input-select {:value (attr->string font-id)
                             :on-change on-font-family-change}
       (when (= font-id :multiple)
         [:option {:value ""} (t locale "settings.multiple")])
       [:& font-select-optgroups]]]

     [:div.row-flex
      (let [size-options [8 9 10 11 12 14 18 24 36 48 72]
            size-options (if (= font-size :multiple) (concat [{:value "" :label "--"}] size-options) size-options)]
        [:& editable-select
         {:value (attr->string font-size)
          :class "input-option"
          :options size-options
          :type "number"
          :placeholder "--"
          :on-change on-font-size-change}])

      [:select.input-select {:value (attr->string font-variant-id)
                             :on-change on-font-variant-change}
       (when (= font-size :multiple)
         [:option {:value ""} "--"])
       (for [variant (:variants font)]
         [:option {:value (:id variant)
                   :key (pr-str variant)}
          (:name variant)])]]]))


(mf/defc text-align-options
  [{:keys [editor ids values locale] :as props}]
  (let [{:keys [text-align]} values

        text-align (or text-align "left")

        on-change
        (fn [event new-align]
          (run! #(st/emit! (dwt/update-paragraph-attrs
                             {:id %
                              :editor editor
                              :attrs {:text-align new-align}}))
                ids))]

    ;; --- Align
    [:div.row-flex.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-left")
       :class (dom/classnames :current (= "left" text-align))
       :on-click #(on-change % "left")}
      i/text-align-left]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-center")
       :class (dom/classnames :current (= "center" text-align))
       :on-click #(on-change % "center")}
      i/text-align-center]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-right")
       :class (dom/classnames :current (= "right" text-align))
       :on-click #(on-change % "right")}
      i/text-align-right]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-justify")
       :class (dom/classnames :current (= "justify" text-align))
       :on-click #(on-change % "justify")}
      i/text-align-justify]]))


(mf/defc spacing-options
  [{:keys [editor ids values locale] :as props}]
  (let [{:keys [line-height
                letter-spacing]} values

        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")

        on-change
        (fn [event attr]
          (let [new-spacing (-> (dom/get-target event)
                                (dom/get-value))]
            (run! #(st/emit! (dwt/update-text-attrs
                               {:id %
                                :editor editor
                                :attrs {attr new-spacing}}))
                  ids)))]
    [:div.row-flex
     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.line-height")}
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
       {:alt (t locale "workspace.options.text-options.letter-spacing")}
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
  [{:keys [editor ids values locale] :as props}]
  (let [{:keys [vertical-align]} values

        vertical-align (or vertical-align "top")

        on-change
        (fn [event new-align]
          (run! #(st/emit! (dwt/update-root-attrs
                             {:id %
                              :editor editor
                              :attrs {:vertical-align new-align}}))
                ids))]

    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.text-options.vertical-align")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.align-top")
        :class (dom/classnames :current (= "top" vertical-align))
        :on-click #(on-change % "top")}
       i/align-top]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.align-middle")
        :class (dom/classnames :current (= "center" vertical-align))
        :on-click #(on-change % "center")}
       i/align-middle]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.align-bottom")
        :class (dom/classnames :current (= "bottom" vertical-align))
        :on-click #(on-change % "bottom")}
       i/align-bottom]]]))

(mf/defc text-decoration-options
  [{:keys [editor ids values locale] :as props}]
  (let [{:keys [text-decoration]} values

        text-decoration (or text-decoration "none")

        on-change
        (fn [event type]
          (run! #(st/emit! (dwt/update-text-attrs
                             {:id %
                              :editor editor
                              :attrs {:text-decoration type}}))
                ids))]
    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.text-options.decoration")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.none")
        :class (dom/classnames :current (= "none" text-decoration))
        :on-click #(on-change % "none")}
       i/minus]

      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.underline")
        :class (dom/classnames :current (= "underline" text-decoration))
        :on-click #(on-change % "underline")}
       i/underline]

      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.strikethrough")
        :class (dom/classnames :current (= "line-through" text-decoration))
        :on-click #(on-change % "line-through")}
       i/strikethrough]]]))

(mf/defc text-transform-options
  [{:keys [editor ids values locale] :as props}]
  (let [{:keys [text-transform]} values

        text-transform (or text-transform "none")

        on-change
        (fn [event type]
          (run! #(st/emit! (dwt/update-text-attrs
                             {:id %
                              :editor editor
                              :attrs {:text-transform type}}))
                ids))]
    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.text-options.text-case")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.none")
        :class (dom/classnames :current (= "none" text-transform))
        :on-click #(on-change % "none")}
       i/minus]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.uppercase")
        :class (dom/classnames :current (= "uppercase" text-transform))
        :on-click #(on-change % "uppercase")}
       i/uppercase]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.lowercase")
        :class (dom/classnames :current (= "lowercase" text-transform))
        :on-click #(on-change % "lowercase")}
       i/lowercase]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.titlecase")
        :class (dom/classnames :current (= "capitalize" text-transform))
        :on-click #(on-change % "capitalize")}
       i/titlecase]]]))

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [ids
           type
           editor
           font-values
           align-values
           spacing-values
           valign-values
           decoration-values
           transform-values] :as props}]
  (let [locale (mf/deref i18n/locale)
        label (case type
                :multiple (t locale "workspace.options.text-options.title-selection")
                :group (t locale "workspace.options.text-options.title-group")
                (t locale "workspace.options.text-options.title"))]
   [:div.element-set
    [:div.element-set-title label]
    [:div.element-set-content
     [:& font-options {:editor editor :ids ids :values font-values :locale locale}]
     [:& text-align-options {:editor editor :ids ids :values align-values :locale locale}]
     [:& spacing-options {:editor editor :ids ids :values spacing-values :locale locale}]
     [:& vertical-align-options {:editor editor :ids ids :values valign-values :locale locale}]
     [:& text-decoration-options {:editor editor :ids ids :values decoration-values :locale locale}]
     [:& text-transform-options {:editor editor :ids ids :values transform-values :locale locale}]]]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)

        local (deref refs/workspace-local)
        editor (get-in local [:editors (:id shape)])

        measure-values (select-keys shape measure-attrs)

        fill-values (dwt/current-text-values
                      {:editor editor
                       :shape shape
                       :attrs text-fill-attrs})

        converted-fill-values {:fill-color (:fill fill-values)
                               :fill-opacity (:opacity fill-values)}

        font-values (dwt/current-text-values
                      {:editor editor
                       :shape shape
                       :attrs text-font-attrs})

        align-values (dwt/current-paragraph-values
                       {:editor editor
                        :shape shape
                        :attrs text-align-attrs})

        spacing-values (dwt/current-text-values
                         {:editor editor
                          :shape shape
                          :attrs text-spacing-attrs})

        valign-values (dwt/current-root-values
                        {:editor editor
                         :shape shape
                         :attrs text-valign-attrs})

        decoration-values (dwt/current-text-values
                            {:editor editor
                             :shape shape
                             :attrs text-decoration-attrs})

        transform-values (dwt/current-text-values
                            {:editor editor
                             :shape shape
                             :attrs text-transform-attrs})]
    [:*
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values}]
     [:& fill-menu {:ids ids
                    :type type
                    :values converted-fill-values
                    :editor editor}]
     [:& text-menu {:ids ids
                    :type type
                    :editor editor
                    :font-values font-values
                    :align-values align-values
                    :spacing-values spacing-values
                    :valign-values valign-values
                    :decoration-values decoration-values
                    :transform-values transform-values}]]))
