;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
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
   :layout-item-align-self  ;; :start :end :center :stretch :baseline
   ])

(mf/defc margin-section
  [{:keys [values change-margin-style on-margin-change] :as props}]

  (let [margin-type (or (:layout-item-margin-type values) :simple)
        margins (if (nil? (:layout-item-margin values))
                  {:m1 0 :m2 0 :m3 0 :m4 0}
                  (:layout-item-margin values))
        rx (if (and (not (= :multiple (:layout-item-margin-type values)))
                    (apply = (vals margins)))
             (:m1 margins)
             "--")]

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
            :value rx}]]]

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
              :value (or (-> values :layout-item-margin num) 0)}]]]))]]))

(mf/defc element-behavior
  [{:keys [is-layout-container? is-layout-child? layout-item-h-sizing layout-item-v-sizing on-change-behavior] :as props}]
  (let [fill? is-layout-child?
        auto? is-layout-container?]

    [:div.btn-wrapper
     [:div.layout-behavior.horizontal
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "Fix width"
        :class  (dom/classnames :active (= layout-item-h-sizing :fix))
        :on-click #(on-change-behavior :h :fix)}
       i/auto-fix-layout]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Width 100%"
          :class  (dom/classnames :active (= layout-item-h-sizing :fill))
          :on-click #(on-change-behavior :h :fill)}
          i/auto-fill])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Fit content"
          :class  (dom/classnames :active (= layout-item-h-sizing :auto))
          :on-click #(on-change-behavior :h :auto)}
         i/auto-hug])]

     [:div.layout-behavior
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "Fix height"
        :class  (dom/classnames :active (= layout-item-v-sizing :fix))
        :on-click #(on-change-behavior :v :fix)}
        i/auto-fix-layout]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Height 100%"
          :class  (dom/classnames :active (= layout-item-v-sizing :fill))
          :on-click #(on-change-behavior :v :fill)}
         i/auto-fill])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom-left
         {:alt "Fit content"
          :class  (dom/classnames :active (= layout-item-v-sizing :auto))
          :on-click #(on-change-behavior :v :auto)}
         i/auto-hug])]]))


(mf/defc align-self-row
  [{:keys [is-col? align-self set-align-self] :as props}]
  (let [dir-v [:start :center :end #_:stretch #_:baseline]]
    [:div.align-self-style
     (for [align dir-v]
       [:button.align-self.tooltip.tooltip-bottom
        {:class    (dom/classnames :active  (= align-self align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (dm/str "Align self " (d/name align))
         :on-click #(set-align-self align)
         :key (str "align-self" align)}
        (get-layout-flex-icon :align-self align is-col?)])]))

(mf/defc layout-item-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [ids values is-layout-child? is-layout-container?] :as props}]

  (let [open?             (mf/use-state false)
        toggle-open       (fn [] (swap! open? not))

        selection-parents-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        selection-parents     (mf/deref selection-parents-ref)

        change-margin-style
        (fn [type]
          (st/emit! (dwsl/update-layout-child ids {:layout-item-margin-type type})))

        align-self         (:layout-item-align-self values)
        set-align-self     (fn [value]
                             (if (= align-self value)
                               (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
                               (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self value}))))

        is-col? (every? ctl/col? selection-parents)

        on-margin-change
        (fn [type val]
          (if (= type :simple)
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m1 val :m2 val :m3 val :m4 val}}))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {type val}}))))

        on-change-behavior
        (fn [dir value]
          (if (= dir :h)
            (st/emit! (dwsl/update-layout-child ids {:layout-item-h-sizing value}))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-v-sizing value}))))

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
                             :layout-item-v-sizing (or (:layout-item-v-sizing values) :fix)
                             :layout-item-h-sizing (or (:layout-item-h-sizing values) :fix)
                             :on-change-behavior on-change-behavior}]]


      (when is-layout-child?
        [:& margin-section {:values values
                            :change-margin-style change-margin-style
                            :on-margin-change on-margin-change}])

      [:div.advanced-ops-container
       [:button.advanced-ops.toltip.tooltip-bottom
        {:on-click toggle-open
         :alt (tr "workspace.options.layout-item.advanced-ops")}
        [:span.icon i/actions]
        [:span (tr "workspace.options.layout-item.advanced-ops")]]]

      (when @open?
        [:div.advanced-ops-body
         (when is-layout-child?
           [:div.layout-row
            [:div.direction-wrap.row-title "Align"]
            [:div.btn-wrapper
             [:& align-self-row {:is-col? is-col?
                                 :align-self align-self
                                 :set-align-self set-align-self}]]])
         [:div.input-wrapper
          (for  [item [:layout-item-max-h :layout-item-min-h :layout-item-max-w :layout-item-min-w]]
            [:div.tooltip.tooltip-bottom
             {:key   (d/name item)
              :alt   (tr (dm/str "workspace.options.layout-item.title." (d/name item)))
              :class (dom/classnames "maxH" (= item :layout-item-max-h)
                                     "minH" (= item :layout-item-min-h)
                                     "maxW" (= item :layout-item-max-w)
                                     "minW" (= item :layout-item-min-w))}
             [:div.input-element
              {:alt   (tr (dm/str "workspace.options.layout-item." (d/name item)))}
              [:> numeric-input
               {:no-validate true
                :min 0
                :data-wrap true
                :placeholder "--"
                :on-click #(dom/select-target %)
                :on-change (partial on-size-change item)
                :value (get values item)
                :nillable true}]]])]])]]))
