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
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layout-item-attrs
  [:layout-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
   :layout-margin-type ;; :simple :multiple
   :layout-h-behavior  ;; :fill :fix :auto
   :layout-v-behavior  ;; :fill :fix :auto
   :layout-max-h       ;; num
   :layout-min-h       ;; num
   :layout-max-w       ;; num
   :layout-min-w ])    ;; num

(mf/defc margin-section
  [{:keys [values change-margin-style on-margin-change] :as props}]

  (let [margin-type (or (:layout-margin-type values) :simple)]

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
           :on-click #(dom/select-target %)
           :on-change (partial on-margin-change :simple)
           :value (or (-> values :layout-margin :m1) 0)}]]]

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
              :on-click #(dom/select-target %)
              :on-change (partial on-margin-change num)
              :value (or (-> values :layout-margin num) 0)}]]])])]))

(mf/defc element-behavior
  [{:keys [is-layout-container? is-layout-child? layout-h-behavior layout-v-behavior on-change-behavior] :as props}]
  (let [fill? is-layout-child?
        auto?  is-layout-container?]

    [:div.layout-behavior
     [:div.button-wrapper.horizontal
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "horizontal fix"
        :class  (dom/classnames :activated (= layout-h-behavior :fix))
        :on-click #(on-change-behavior :h :fix)}
       [:span.icon i/auto-fix-layout]]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "horizontal fill"
          :class  (dom/classnames :activated (= layout-h-behavior :fill))
          :on-click #(on-change-behavior :h :fill)}
         [:span.icon i/auto-fill]])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "horizontal auto"
          :class  (dom/classnames :activated (= layout-v-behavior :auto))
          :on-click #(on-change-behavior :h :auto)}
         [:span.icon i/auto-hug]])]

     [:div.button-wrapper
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "vertical fix"
        :class  (dom/classnames :activated (= layout-v-behavior :fix))
        :on-click #(on-change-behavior :v :fix)}
       [:span.icon i/auto-fix-layout]]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "vertical fill"
          :class  (dom/classnames :activated (= layout-v-behavior :fill))
          :on-click #(on-change-behavior :v :fill)}
         [:span.icon i/auto-fill]])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "vertical auto"
          :class  (dom/classnames :activated (= layout-v-behavior :auto))
          :on-click #(on-change-behavior :v :auto)}
         [:span.icon i/auto-hug]])]]))

(mf/defc layout-item-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [ids _type values is-layout-child? is-layout-container?] :as props}]
  (let [open?             (mf/use-state false)
        toggle-open       (fn [] (swap! open? not))

        change-margin-style
        (fn [type]
          (st/emit! (dwsl/update-layout-child ids {:layout-margin-type type})))

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
      [:span (tr "workspace.options.layout-item.title")]]

     [:div.element-set-content.layout-item-menu
      [:& element-behavior {:is-layout-child? is-layout-child?
                            :is-layout-container? is-layout-container?
                            :layout-v-behavior (or (:layout-v-behavior values) :fix)
                            :layout-h-behavior (or (:layout-h-behavior values) :fix)
                            :on-change-behavior on-change-behavior}]

      [:div.margin [:& margin-section {:values values
                                       :change-margin-style change-margin-style
                                       :on-margin-change on-margin-change}]]
      [:div.advanced-ops-container
       [:div.advanced-ops.toltip.tooltip-bottom
        {:on-click toggle-open
         :alt (tr "workspace.options.layout-item.advanced-ops")}
        [:div.element-set-actions-button i/actions]
        [:span (tr "workspace.options.layout-item.advanced-ops")]]]

      (when @open?
        [:div.advanced-ops-body
         (for  [item [:layout-max-h :layout-min-h :layout-max-w :layout-min-w]]
           [:div.input-element
            {:key (d/name item)
             :alt   (tr (dm/str "workspace.options.layout-item." (d/name item)))
             :title (tr (dm/str "workspace.options.layout-item." (d/name item)))
             :class (dom/classnames "maxH" (= item :layout-max-h)
                                    "minH" (= item :layout-min-h)
                                    "maxW" (= item :layout-max-w)
                                    "minW" (= item :layout-min-w))}

            [:> numeric-input
             {:no-validate true
              :min 0
              :data-wrap true
              :placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-size-change item)
              :value (get values item)}]])])]]))
