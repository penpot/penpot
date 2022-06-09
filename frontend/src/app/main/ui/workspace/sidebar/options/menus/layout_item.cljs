;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(def layout-item-attrs
  [:margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
   :margin-type ;; :simple :multiple
   :h-behavior  ;; :fill :fix :auto
   :v-behavior  ;; :fill :fix :auto
   :max-h       ;; num
   :min-h       ;; num
   :max-w       ;; num
   :min-w ])    ;; num
(mf/defc margin-section
  [{:keys [test-values change-margin-style select-all on-margin-change] :as props}]

  (let [margin-type (:margin-type @test-values)]

    [:div.row-flex
     [:div.margin-options
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

     (cond
       (= margin-type :simple)
       [:div.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.layout.margin-all")}
        [:div.input-element.mini

         [:> numeric-input
          {:placeholder "--"
           :on-click select-all
           :on-change (partial on-margin-change :simple)
           :value (:m1 (:margin @test-values))}]]]

       (= margin-type :multiple)
       [:*
        (for [num [:m1 :m2 :m3 :m4]]
          [:div.tooltip.tooltip-bottom
           {:class (dm/str "margin-" (d/name num))
            :key (dm/str "margin-" (d/name num))
            :alt (case num
                   :m1 (tr "workspace.options.layout.top")
                   :m2 (tr "workspace.options.layout.right")
                   :m3 (tr "workspace.options.layout.bottom")
                   :m4 (tr "workspace.options.layout.left"))}
           [:div.input-element.mini
            [:> numeric-input
             {:placeholder "--"
              :on-click select-all
              :on-change (partial on-margin-change num)
              :value (num (:margin @test-values))}]]])])]))

(mf/defc element-behavior
  [{:keys [is-layout-container? is-layout-item? h-behavior v-behavior on-change-behavior] :as props}]
  (let [auto?  is-layout-container?
        fill? (and (= true is-layout-item?) (not= true is-layout-container?))]

    [:div.layout-behavior
     [:div.button-wrapper.horizontal
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "horizontal fix"
        :class  (dom/classnames :activated (= h-behavior :fix))
        :on-click #(on-change-behavior :h :fix)}
       [:span.icon i/auto-fix-layout]]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "horizontal fill"
          :class  (dom/classnames :activated (= h-behavior :fill))
          :on-click #(on-change-behavior :h :fill)}
         [:span.icon i/auto-fill]])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "horizontal auto"
          :class  (dom/classnames :activated (= h-behavior :auto))
          :on-click #(on-change-behavior :h :auto)}
         [:span.icon i/auto-hug]])]
     [:div.button-wrapper
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "vertical fix"
        :class  (dom/classnames :activated (= v-behavior :fix))
        :on-click #(on-change-behavior :v :fix)}
       [:span.icon i/auto-fix-layout]]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "vertical fill"
          :class  (dom/classnames :activated (= v-behavior :fill))
          :on-click #(on-change-behavior :v :fill)}
         [:span.icon i/auto-fill]])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "vertical auto"
          :class  (dom/classnames :activated (= v-behavior :auto))
          :on-click #(on-change-behavior :v :auto)}
         [:span.icon i/auto-hug]])]]))

(mf/defc layout-item-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [_ids _type _values] :as props}]
  (let [test-values (mf/use-state {:margin {:m1 0 :m2 0 :m3 0 :m4 0}
                                   :margin-type :simple
                                   :h-behavior :fill
                                   :v-behavior :fill
                                   :max-h 100
                                   :min-h 100
                                   :max-w 100
                                   :min-w 100})
        open?             (mf/use-state false)
        toggle-open       (fn [] (swap! open? not))
        is-layout-container? true
        is-layout-item? true
        change-margin-style
        (fn [type]
          (swap! test-values assoc :margin-type type))

        select-all #(dom/select-target %)

        on-margin-change
        (fn [type val]
          (if (= type :simple)
            (swap! test-values assoc :margin {:m1 val :m2 val :m3 val :m4 val})
            (swap! test-values assoc-in [:margin type] val)))

        on-change-behavior
        (fn [dir value]
          (if (= dir :h)
            (swap! test-values assoc :h-behavior value)
            (swap! test-values assoc :v-behavior value)))

        on-size-change
        (fn [measure value]
          (swap! test-values assoc measure value))]
    [:div.element-set
     [:div.element-set-title
      [:span (tr "workspace.options.layout-item.title")]]
     [:div.element-set-content.layout-item-menu
      [:& element-behavior {:is-layout-container? is-layout-container?
                            :is-layout-item? is-layout-item?
                            :v-behavior (:v-behavior @test-values)
                            :h-behavior (:h-behavior @test-values)
                            :on-change-behavior on-change-behavior}]
      [:div.margin [:& margin-section {:test-values test-values
                                       :change-margin-style change-margin-style
                                       :select-all select-all
                                       :on-margin-change on-margin-change}]]
      [:div.advanced-ops-container
       [:div.advanced-ops.toltip.tooltip-bottom
        {:on-click toggle-open
         :alt (tr "workspace.options.layout-item.advanced-ops")}
        [:div.element-set-actions-button i/actions]
        [:span (tr "workspace.options.layout-item.advanced-ops")]]]
      (when (= true @open?)
        [:div.advanced-ops-body
         (for  [item [:max-h :min-h :max-w :min-w]]
           [:div.input-element
            {:alt   (tr (dm/str "workspace.options.layout-item." (d/name item)))
             :title (tr (dm/str "workspace.options.layout-item." (d/name item)))
             :class (dom/classnames "maxH" (= item :max-h)
                                    "minH" (= item :min-h)
                                    "maxW" (= item :max-w)
                                    "minW" (= item :min-w))
             :key item}
            [:> numeric-input
             {:no-validate true
              :min 0
              :data-wrap true
              :placeholder "--"
              :on-click select-all
              :on-change (partial on-size-change item)
              :value (item @test-values)}]])])]]))
