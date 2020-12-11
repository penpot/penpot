;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.typography
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.main.ui.icons :as i]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.common.data :as d]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.fonts :as fonts]
   [app.util.dom :as dom]
   [app.util.text :as ut]
   [app.util.timers :as ts]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]))

(defn- attr->string [value]
  (if (= value :multiple)
    ""
    (str value)))

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
  [{:keys [editor ids values locale on-change] :as props}]
  (let [{:keys [font-id
                font-size
                font-variant-id]} values

        font-id (or font-id (:font-id ut/default-text-attrs))
        font-size (or font-size (:font-size ut/default-text-attrs))
        font-variant-id (or font-variant-id (:font-variant-id ut/default-text-attrs))

        fonts     (mf/deref fonts/fontsdb)
        font      (get fonts font-id)

        change-font
        (fn [new-font-id]
          (let [{:keys [family] :as font} (get fonts new-font-id)
                {:keys [id name weight style]} (fonts/get-default-variant font)]
            (on-change {:font-id new-font-id
                        :font-family family
                        :font-variant-id (or id name)
                        :font-weight weight
                        :font-style style})))

        on-font-family-change
        (fn [event]
          (let [new-font-id (dom/get-target-val event)]
            (when-not (str/empty? new-font-id)
              (let [font (get fonts new-font-id)]
                (fonts/ensure-loaded! new-font-id (partial change-font new-font-id))))))

        on-font-size-change
        (fn [new-font-size]
          (when-not (str/empty? new-font-size)
            (on-change {:font-size (str new-font-size)})))

        on-font-variant-change
        (fn [event]
          (let [new-variant-id (dom/get-target-val event)
                variant (d/seek #(= new-variant-id (:id %)) (:variants font))]
            (on-change {:font-id (:id font)
                        :font-family (:family font)
                        :font-variant-id new-variant-id
                        :font-weight (:weight variant)
                        :font-style (:style variant)})))]

    [:*
     [:div.row-flex
      [:select.input-select.font-option
       {:value (attr->string font-id)
        :on-change on-font-family-change}
       (when (= font-id :multiple)
         [:option {:value ""} (t locale "settings.multiple")])
       [:& font-select-optgroups]]]

     [:div.row-flex
      (let [size-options [8 9 10 11 12 14 18 24 36 48 72]
            size-options (if (= font-size :multiple) (into [""] size-options) size-options)]
        [:& editable-select
         {:value (attr->string font-size)
          :class "input-option size-option"
          :options size-options
          :type "number"
          :placeholder "--"
          :on-change on-font-size-change}])

      [:select.input-select.variant-option
       {:disabled (= font-id :multiple)
        :value (attr->string font-variant-id)
        :on-change on-font-variant-change}
       (when (or (= font-id :multiple) (= font-variant-id :multiple))
         [:option {:value ""} "--"])
       (for [variant (:variants font)]
         [:option {:value (:id variant)
                   :key (pr-str variant)}
          (:name variant)])]]]))


(mf/defc spacing-options
  [{:keys [editor ids values locale on-change] :as props}]
  (let [{:keys [line-height
                letter-spacing]} values

        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")

        handle-change
        (fn [event attr]
          (let [new-spacing (dom/get-target-val event)]
            (on-change {attr new-spacing})))]

    [:div.spacing-options
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
        :on-change #(handle-change % :line-height)}]]

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
        :on-change #(handle-change % :letter-spacing)}]]]))

(mf/defc text-transform-options
  [{:keys [editor ids values locale on-change] :as props}]
  (let [{:keys [text-transform]} values

        text-transform (or text-transform "none")

        handle-change
        (fn [event type]
          (on-change {:text-transform type}))]
    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.none")
       :class (dom/classnames :current (= "none" text-transform))
       :on-click #(handle-change % "none")}
      i/minus]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.uppercase")
       :class (dom/classnames :current (= "uppercase" text-transform))
       :on-click #(handle-change % "uppercase")}
      i/uppercase]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.lowercase")
       :class (dom/classnames :current (= "lowercase" text-transform))
       :on-click #(handle-change % "lowercase")}
      i/lowercase]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.titlecase")
       :class (dom/classnames :current (= "capitalize" text-transform))
       :on-click #(handle-change % "capitalize")}
      i/titlecase]]))

(mf/defc typography-options
  [{:keys [ids editor values on-change]}]
  (let [locale (mf/deref i18n/locale)
        opts #js {:editor editor
                  :ids ids
                  :values values
                  :locale locale
                  :on-change on-change}]

    [:div.element-set-content
     [:> font-options opts]
     [:div.row-flex
      [:> spacing-options opts]
      [:> text-transform-options opts]]]))


(mf/defc typography-entry
  [{:keys [typography read-only? on-select on-change on-deattach on-context-menu editting? focus-name? file]}]
  (let [locale (mf/deref i18n/locale)
        open? (mf/use-state editting?)
        selected (mf/deref refs/selected-shapes)
        hover-deattach (mf/use-state false)
        name-input-ref (mf/use-ref nil)

        #_(rt/resolve router :workspace
                      {:project-id (:project-id file)
                       :file-id (:id file)}
                      {:page-id (get-in file [:data :pages 0])})
        handle-go-to-edit
        (fn [] (st/emit! (rt/nav :workspace {:project-id (:project-id file)
                                             :file-id (:id file)}
                                 {:page-id (get-in file [:data :pages 0])})))]

    (mf/use-effect
     (mf/deps editting?)
     (fn []
       (when editting?
         (reset! open? editting?))))

    (mf/use-effect
     (mf/deps focus-name?)
     (fn []
       (when focus-name?
         (ts/schedule 100
          #(when-let [node (mf/ref-val name-input-ref)]
             (dom/focus! node)
             (dom/select-text! node))))))

    [:*
     [:div.element-set-options-group.typography-entry
      [:div.typography-selection-wrapper
       {:class (when on-select "is-selectable")
        :on-click on-select
        :on-context-menu on-context-menu}
       [:div.typography-sample
        {:style {:font-family (:font-family typography)
                 :font-weight (:font-weight typography)
                 :font-style (:font-style typography)}}
        (t locale "workspace.assets.typography.sample")]
       [:div.typography-name (:name typography)]]
      [:div.element-set-actions
       (when on-deattach
         [:div.element-set-actions-button
          {:on-mouse-enter #(reset! hover-deattach true)
           :on-mouse-leave #(reset! hover-deattach false)
           :on-click on-deattach}
          (if @hover-deattach i/unchain i/chain)])

       [:div.element-set-actions-button
        {:on-click #(reset! open? true)}
        i/actions]]]

     [:& advanced-options {:visible? @open?
                           :on-close #(reset! open? false)}
      (if read-only?
        [:div.element-set-content.typography-read-only-data
         [:div.row-flex.typography-name
          [:span (:name typography)]]

         [:div.row-flex
          [:span.label (t locale "workspace.assets.typography.font-id")]
          [:span (:font-id typography)]]

         [:div.row-flex
          [:span.label (t locale "workspace.assets.typography.font-variant-id")]
          [:span (:font-variant-id typography)]]

         [:div.row-flex
          [:span.label (t locale "workspace.assets.typography.font-size")]
          [:span (:font-size typography)]]

         [:div.row-flex
          [:span.label (t locale "workspace.assets.typography.line-height")]
          [:span (:line-height typography)]]

         [:div.row-flex
          [:span.label (t locale "workspace.assets.typography.letter-spacing")]
          [:span (:letter-spacing typography)]]

         [:div.row-flex
          [:span.label (t locale "workspace.assets.typography.text-transform")]
          [:span (:text-transform typography)]]

         [:div.go-to-lib-button
          {:on-click handle-go-to-edit}
          (t locale "workspace.assets.typography.go-to-edit")]]

        [:*
         [:div.element-set-content
          [:div.row-flex
           [:input.element-name.adv-typography-name
            {:type "text"
             :ref name-input-ref
             :value (:name typography)
             :on-change #(on-change {:name (dom/get-target-val %)})}]]]
         [:& typography-options {:values typography
                                 :on-change on-change}]])]]))
