;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.grid-cell
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :as lyc]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc set-self-alignment
  [{:keys [is-col? alignment set-alignment] :as props}]
  (let [dir-v [:auto :start :center :end :stretch #_:baseline]]
    [:div.align-self-style
     (for [align dir-v]
       [:button.align-self.tooltip.tooltip-bottom
        {:class    (dom/classnames :active  (= alignment align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (dm/str "Align self " (d/name align)) ;; TODO fix this tooltip
         :on-click #(set-alignment align)
         :key (str "align-self" align)}
        (lyc/get-layout-flex-icon :align-self align is-col?)])]))


(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [_shape row column] :as props}]

  (let [position-mode (mf/use-state :auto) ;; TODO this should come from shape

        set-position-mode (fn [mode]
                            (reset! position-mode mode))


        align-self          (mf/use-state :auto) ;; TODO this should come from shape
        justify-alignment   (mf/use-state :auto) ;; TODO this should come from shape
        set-alignment     (fn [value]
                            (reset! align-self value)
                            #_(if (= align-self value)
                                (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
                                (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self value}))))
        set-justify-self     (fn [value]
                               (reset! justify-alignment value)
                               #_(if (= align-self value)
                                   (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
                                   (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self value}))))
        column-start column
        column-end (inc column)
        row-start row
        row-end (inc row)

        on-change
        (fn [_side _orientation _value]
          ;; TODO
          #_(if (= orientation :column)
            (case side
              :all ((reset! column-start value)
                    (reset! column-end value))
              :start (reset! column-start value)
              :end (reset! column-end value))
            (case side
              :all ((reset! row-start value)
                    (reset! row-end value))
              :start (reset! row-start value)
              :end (reset! row-end value))))

        area-name (mf/use-state "header") ;; TODO this should come from shape

        on-area-name-change (fn [value]
                              (reset! area-name value))
        on-key-press (fn [_event])]

    [:div.element-set
     [:div.element-set-title
      [:span "Grid Cell"]]

     [:div.element-set-content.layout-grid-item-menu
      [:div.layout-row
       [:div.row-title.sizing "Position"]
       [:div.position-wrapper
        [:button.position-btn
         {:on-click #(set-position-mode :auto)
          :class (dom/classnames :active (= :auto @position-mode))} "Auto"]
        [:button.position-btn
         {:on-click #(set-position-mode :manual)
          :class (dom/classnames :active (= :manual @position-mode))} "Manual"]
        [:button.position-btn
         {:on-click #(set-position-mode :area)
          :class (dom/classnames :active (= :area @position-mode))} "Area"]]]
      [:div.manage-grid-columns
       (when (= :auto @position-mode)
         [:div.grid-auto
          [:div.grid-columns-auto
           [:span.icon i/layout-rows]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :all :column) ;; TODO cambiar este on-change y el value
              :value column-start}]]]
          [:div.grid-rows-auto
           [:span.icon i/layout-columns]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :all :row) ;; TODO cambiar este on-change y el value
              :value row-start}]]]])
       (when (= :area @position-mode)
         [:div.input-wrapper
          [:input.input-text
           {:key "grid-area-name"
            :id "grid-area-name"
            :type "text"
            :aria-label "grid-area-name"
            :placeholder "--"
            :default-value @area-name
            :auto-complete "off"
            :on-change on-area-name-change
            :on-key-press on-key-press}]])

       (when (or (= :manual @position-mode) (= :area @position-mode))
         [:div.grid-manual
          [:div.grid-columns-auto
           [:span.icon i/layout-rows]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :start :column)
              :value column-start}]
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :end :column)
              :value column-end}]]]
          [:div.grid-rows-auto
           [:span.icon i/layout-columns]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :start :row)
              :value row-start}]
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :end :row)
              :value row-end}]]]])]

      [:div.layout-row
       [:div.row-title "Align"]
       [:div.btn-wrapper
        [:& set-self-alignment {:is-col? false
                                :alignment @align-self
                                :set-alignment set-alignment}]]]


      [:div.layout-row
       [:div.row-title "Justify"]
       [:div.btn-wrapper
        [:& set-self-alignment {:is-col? true
                                :alignment @justify-alignment
                                :set-alignment set-justify-self}]]]]]))
