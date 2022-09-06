;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [get-layout-flex-icon]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layout-item-attrs
  [:layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
   :layout-item-margin-type ;; :simple :multiple
   :layout-item-h-sizing    ;; :fill :fix :auto
   :layout-item-v-sizing    ;; :fill :fix :auto
   :layout-item-max-h       ;; num
   :layout-item-min-h       ;; num
   :layout-item-max-w       ;; num
   :layout-item-min-w       ;; num
   :layout-item-align-self  ;; :start :end :center :strech :baseline
   ])

(mf/defc margin-section
  [{:keys [values change-margin-style on-margin-change] :as props}]

  (let [margin-type (or (:layout-margin-type values) :simple)]

    [:div.margin-row
     [:div.margin-icons
      [:div.margin-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= margin-type :simple))
        :alt (tr "workspace.options.layout.margin-simple")
        :on-click #(change-margin-style :simple)}
       i/auto-margin]
      [:div.margin-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= margin-type :multiple))
        :alt (tr "workspace.options.layout.margin")
        :on-click #(change-margin-style :multiple)}
       i/auto-margin-side]]
     [:div.wrapper
      (cond
        (= margin-type :simple)
        [:div.tooltip.tooltip-bottom
         {:alt (tr "workspace.options.layout.margin-all")}
         [:div.input-element.mini

          [:> numeric-input
           {:placeholder "--"
            :on-click #(dom/select-target %)
            :on-change (partial on-margin-change :simple)
            :value (or (-> values :layout-margin :m1) 0)}]]]

        (= margin-type :multiple)
        (for [num [:m1 :m2 :m3 :m4]]
          [:div.tooltip.tooltip-bottom
           {:key (dm/str "margin-" (d/name num))
            :alt (case num
                   :m1 "Top"
                   :m2 "Right"
                   :m3 "Bottom"
                   :m4 "Left")}
           [:div.input-element.mini
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-margin-change num)
              :value (or (-> values :layout-margin num) 0)}]]]))]]))

(mf/defc element-behavior
  [{:keys [is-layout-container? is-layout-child? layout-h-behavior layout-v-behavior on-change-behavior] :as props}]
  (let [fill? is-layout-child?
        auto?  is-layout-container?]

    [:div.btn-wrapper
     [:div.layout-behavior.horizontal
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "Fix width"
        :class  (dom/classnames :activated (= layout-h-behavior :fix))
        :on-click #(on-change-behavior :h :fix)}
       i/auto-fix-layout]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Width 100%"
          :class  (dom/classnames :activated (= layout-h-behavior :fill))
          :on-click #(on-change-behavior :h :fill)}
          i/auto-fill])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Fit content"
          :class  (dom/classnames :activated (= layout-v-behavior :auto))
          :on-click #(on-change-behavior :h :auto)}
         i/auto-hug])]

     [:div.layout-behavior
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "Fix height"
        :class  (dom/classnames :activated (= layout-v-behavior :fix))
        :on-click #(on-change-behavior :v :fix)}
        i/auto-fix-layout]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Height 100%"
          :class  (dom/classnames :activated (= layout-v-behavior :fill))
          :on-click #(on-change-behavior :v :fill)}
         i/auto-fill])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom-left
         {:alt "Fit content"
          :class  (dom/classnames :activated (= layout-v-behavior :auto))
          :on-click #(on-change-behavior :v :auto)}
         i/auto-hug])]]))


(mf/defc align-self-row
  [{:keys [is-col? align-self set-align-self] :as props}]
  (let [dir-v [:start :center :end :strech :baseline]]
    [:div.align-self-style
     (for [align dir-v]
       [:button.align-self.tooltip.tooltip-bottom
        {:class    (dom/classnames :active  (= align-self align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (dm/str "Align self " (d/name align)) ;; TODO añadir lineas de texto a tradus
         :on-click #(set-align-self align)
         :key (str "align-self" align)}
        (get-layout-flex-icon :align-self align is-col?)])]))

(mf/defc layout-item-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [ids values is-layout-child? is-layout-container?] :as props}]

  (let [open?             (mf/use-state false)
        toggle-open       (fn [] (swap! open? not))

        change-margin-style
        (fn [type]
          (st/emit! (dwsl/update-layout-child ids {:layout-margin-type type})))

        align-self         (:layout-align-self values)
        set-align-self     (fn [value]
                             (st/emit! (dwsl/update-layout-child ids {:layout-align-self value})))

        saved-dir (:layout-flex-dir values)
        is-col? (or (= :column saved-dir) (= :reverse-column saved-dir))

        on-margin-change
        (fn [type val]
          (if (= type :simple)
            (st/emit! (dwsl/update-layout-child ids {:layout-margin {:m1 val :m2 val :m3 val :m4 val}}))
            (st/emit! (dwsl/update-layout-child ids {:layout-margin {type val}}))))

        on-change-behavior
        (fn [dir value]
          (if (= dir :h)
            (st/emit! (dwsl/update-layout-child ids {:layout-h-behavior value}))
            (st/emit! (dwsl/update-layout-child ids {:layout-v-behavior value}))))

        on-size-change
        (fn [measure value]
          (st/emit! (dwsl/update-layout-child ids {measure value})))]

    [:div.element-set
     [:div.element-set-title
      [:span "Flex elements"]]

     [:div.element-set-content.layout-item-menu
      [:div.layout-row
       [:div.row-title "Sizing"]
       [:& element-behavior {:is-layout-child? is-layout-child?
                             :is-layout-container? is-layout-container?
                             :layout-v-behavior (or (:layout-v-behavior values) :fix)
                             :layout-h-behavior (or (:layout-h-behavior values) :fix)
                             :on-change-behavior on-change-behavior}]]
      

      [:& margin-section {:values values
                          :change-margin-style change-margin-style
                          :on-margin-change on-margin-change}]
      [:div.advanced-ops-container
       [:button.advanced-ops.toltip.tooltip-bottom
        {:on-click toggle-open
         :alt (tr "workspace.options.layout-item.advanced-ops")}
        [:span.icon i/actions]
        [:span (tr "workspace.options.layout-item.advanced-ops")]]]

      (when @open?
        [:div.advanced-ops-body
         [:div.layout-row
          [:div.direction-wrap.row-title "Align"]
          [:div.btn-wrapper
           [:& align-self-row {:is-col? is-col?
                               :align-self align-self
                               :set-align-self set-align-self}]]]
         [:div.input-wrapper
          (for  [item [:layout-max-h :layout-min-h :layout-max-w :layout-min-w]]
            [:div.tooltip.tooltip-bottom
             {:key   (d/name item)
              :alt   (tr (dm/str "workspace.options.layout-item.title." (d/name item)))
              :class (dom/classnames "maxH" (= item :layout-max-h)
                                     "minH" (= item :layout-min-h)
                                     "maxW" (= item :layout-max-w)
                                     "minW" (= item :layout-min-w))}
             [:div.input-element
              {:alt   (tr (dm/str "workspace.options.layout-item." (d/name item)))}
              [:> numeric-input
               {:no-validate true
                :min 0
                :data-wrap true
                :placeholder "--"
                :on-click #(dom/select-target %)
                :on-change (partial on-size-change item)
              ;; :value (get values item)
                :value 100}]]])]])]]
              ))
