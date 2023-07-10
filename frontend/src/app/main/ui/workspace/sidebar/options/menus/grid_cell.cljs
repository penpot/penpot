;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.grid-cell
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :as lyc]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc set-self-alignment
  [{:keys [is-col? alignment set-alignment] :as props}]
  (let [dir-v [:auto :start :center :end :stretch #_:baseline]
        alignment (or alignment :auto)]
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
  [{:keys [shape cell] :as props}]

  (let [{:keys [mode area-name align-self justify-self column column-span row row-span]} cell
        column-end (+ column column-span)
        row-end (+ row row-span)

        cell-mode (or mode :auto)
        cell-mode (if (and (= :auto cell-mode)
                           (or (> (:column-span cell) 1)
                               (> (:row-span cell) 1)))
                    :manual
                    cell-mode)

        set-alignment
        (mf/use-callback
         (mf/deps align-self (:id shape) (:id cell))
         (fn [value]
           (if (= align-self value)
             (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) {:align-self nil}))
             (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) {:align-self value})))))

        set-justify-self
        (mf/use-callback
         (mf/deps justify-self (:id shape) (:id cell))
         (fn [value]
           (if (= justify-self value)
             (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) {:justify-self nil}))
             (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) {:justify-self value})))))

        on-change
        (mf/use-callback
         (mf/deps column row (:id shape) (:id cell))
         (fn [field type value]
           (let [[property value]
                 (cond
                   (and (= type :column) (or (= field :all) (= field :start)))
                   [:column value]

                   (and (= type :column) (= field :end))
                   [:column-span (max 1 (- value column))]

                   (and (= type :row) (or (= field :all) (= field :start)))
                   [:row value]

                   (and (= type :row) (= field :end))
                   [:row-span (max 1 (- value row))])]

             (st/emit! (dwsl/update-grid-cell-position (:id shape) (:id cell) {property value})))))

        on-area-name-change
        (mf/use-callback
         (mf/deps (:id shape) (:id cell))
         (fn [event]
           (let [value (dom/get-value (dom/get-target event))]
             (if (= value "")
               (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) {:area-name nil}))
               (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) {:area-name value}))))))

        set-cell-mode
        (mf/use-callback
         (mf/deps (:id shape) (:id cell))
         (fn [mode]
           (let [props (cond-> {:mode mode}
                         (not= mode :area)
                         (assoc :area-name nil))]
             (st/emit! (dwsl/update-grid-cell (:id shape) (:id cell) props)))))]

    [:div.element-set
     [:div.element-set-title
      [:span "Grid Cell"]]

     [:div.element-set-content.layout-grid-item-menu
      [:div.layout-row
       [:div.row-title.sizing "Position"]
       [:div.position-wrapper
        [:button.position-btn
         {:on-click #(set-cell-mode :auto)
          :class (dom/classnames :active (= :auto cell-mode))} "Auto"]
        [:button.position-btn
         {:on-click #(set-cell-mode :manual)
          :class (dom/classnames :active (= :manual cell-mode))} "Manual"]
        [:button.position-btn
         {:on-click #(set-cell-mode :area)
          :class (dom/classnames :active (= :area cell-mode))} "Area"]]]

      [:div.manage-grid-columns
       (when (= :auto cell-mode)
         [:div.grid-auto
          [:div.grid-columns-auto
           [:span.icon i/layout-rows]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :all :column)
              :value column}]]]
          [:div.grid-rows-auto
           [:span.icon i/layout-columns]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change :all :row)
              :value row}]]]])

       (when (= :area cell-mode)
         [:div.input-wrapper
          [:input.input-text
           {:key (dm/str "name-" (:id cell))
            :id "grid-area-name"
            :type "text"
            :aria-label "grid-area-name"
            :placeholder "--"
            :default-value area-name
            :auto-complete "off"
            :on-change on-area-name-change}]])

       (when (or (= :manual cell-mode) (= :area cell-mode))
         [:div.grid-manual
          [:div.grid-columns-auto
           [:span.icon i/layout-rows]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-pointer-down #(dom/select-target %)
              :on-change (partial on-change :start :column)
              :value column}]
            [:> numeric-input
             {:placeholder "--"
              :on-pointer-down #(dom/select-target %)
              :on-change (partial on-change :end :column)
              :value column-end}]]]
          [:div.grid-rows-auto
           [:span.icon i/layout-columns]
           [:div.input-wrapper
            [:> numeric-input
             {:placeholder "--"
              :on-pointer-down #(dom/select-target %)
              :on-change (partial on-change :start :row)
              :value row}]
            [:> numeric-input
             {:placeholder "--"
              :on-pointer-down #(dom/select-target %)
              :on-change (partial on-change :end :row)
              :value row-end}]]]])]

      [:div.layout-row
       [:div.row-title "Align"]
       [:div.btn-wrapper
        [:& set-self-alignment {:is-col? false
                                :alignment align-self
                                :set-alignment set-alignment}]]]
      [:div.layout-row
       [:div.row-title "Justify"]
       [:div.btn-wrapper
        [:& set-self-alignment {:is-col? true
                                :alignment justify-self
                                :set-alignment set-justify-self}]]]]]))
