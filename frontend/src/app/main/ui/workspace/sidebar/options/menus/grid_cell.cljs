;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.grid-cell
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :as lyc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def cell-props [:id
                 :position
                 :row
                 :row-span
                 :column
                 :column-span
                 :align-self
                 :justify-self
                 :area-name])

(mf/defc set-self-alignment
  [{:keys [is-col? alignment set-alignment] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        dir-v [:auto :start :center :end :stretch #_:baseline]
        alignment (or alignment :auto)
        type (if is-col? "col" "row")]

    (if new-css-system
      [:div {:class (stl/css :self-align-menu)}
       [:& radio-buttons {:selected (d/name alignment)
                          :on-change #(set-alignment (keyword %))
                          :name (dm/str "flex-align-items-" type)}
        [:& radio-button {:value "start"
                          :icon  (if is-col? i/align-self-row-left-refactor i/align-self-column-top-refactor)
                          :title "Align self start"
                          :id     (dm/str "align-self-start-" type)}]

        [:& radio-button {:value "center"
                          :icon  (if is-col? i/align-self-row-center-refactor i/align-self-column-center-refactor)
                          :title "Align self center"
                          :id     (dm/str "align-self-center-" type)}]

        [:& radio-button {:value "end"
                          :icon  (if is-col? i/align-self-row-right-refactor i/align-self-column-bottom-refactor)
                          :title "Align self end"
                          :id     (dm/str "align-self-end-" type)}]

        [:& radio-button {:value "stretch"
                          :icon  (if is-col? i/align-self-row-strech i/align-self-column-strech)
                          :title "Align self stretch"
                          :id     (dm/str "align-self-stretch-" type)}]]]

      [:div.align-self-style
       (for [align dir-v]
         [:button.align-self.tooltip.tooltip-bottom
          {:class    (dom/classnames :active  (= alignment align)
                                     :tooltip-bottom-left (not= align :start)
                                     :tooltip-bottom (= align :start))
           :alt      (dm/str "Align self " (d/name align)) ;; TODO fix this tooltip
           :on-click #(set-alignment align)
           :key (str "align-self" align)}
          (lyc/get-layout-flex-icon :align-self align is-col?)])])))


(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shape cell cells] :as props}]

  (let [new-css-system (mf/use-ctx ctx/new-css-system)

        state* (mf/use-state {:open true})
        open?  (:open @state*)

        cells (hooks/use-equal-memo cells)
        cell (or cell (attrs/get-attrs-multi cells cell-props))

        multiple? (= :multiple (:id cell))
        cell-ids (if (some? cell) [(:id cell)] (->> cells (map :id)))
        cell-ids (hooks/use-equal-memo cell-ids)

        {:keys [position area-name align-self justify-self column column-span row row-span]} cell

        column-end (when (and (d/num? column) (d/num? column-span))
                     (+ column column-span))
        row-end    (when (and (d/num? row) (d/num? row-span))
                     (+ row row-span))

        cell-mode (or position :auto)
        cell-mode (if (and (= :auto cell-mode)
                           (or (> (:column-span cell) 1)
                               (> (:row-span cell) 1)))
                    :manual
                    cell-mode)

        valid-area-cells? (mf/use-memo
                           (mf/deps cells)
                           #(ctl/valid-area-cells? cells))

        set-alignment
        (mf/use-callback
         (mf/deps align-self (:id shape) cell-ids)
         (fn [value]
           (if (= align-self value)
             (st/emit! (dwsl/update-grid-cells (:id shape) cell-ids {:align-self nil}))
             (st/emit! (dwsl/update-grid-cells (:id shape) cell-ids {:align-self value})))))

        set-justify-self
        (mf/use-callback
         (mf/deps justify-self (:id shape) cell-ids)
         (fn [value]
           (if (= justify-self value)
             (st/emit! (dwsl/update-grid-cells (:id shape) cell-ids {:justify-self nil}))
             (st/emit! (dwsl/update-grid-cells (:id shape) cell-ids {:justify-self value})))))

        on-grid-coordinates
        (mf/use-callback
         (mf/deps column row (:id shape) (:id cell))
         (fn [field type value]
           (when-not multiple?
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

               (st/emit! (dwsl/update-grid-cell-position (:id shape) (:id cell) {property value}))))))

        on-area-name-change
        (mf/use-callback
         (mf/deps (:id shape) cell-ids)
         (fn [event]
           (let [value (dom/get-value (dom/get-target event))]
             (if (= value "")
               (st/emit! (dwsl/update-grid-cells (:id shape) cell-ids {:area-name nil}))
               (st/emit! (dwsl/update-grid-cells (:id shape) cell-ids {:area-name value}))))))

        set-cell-mode
        (mf/use-callback
         (mf/deps (:id shape) cell-ids)
         (fn [mode]
           (let [mode (-> mode keyword)]
             (st/emit! (dwsl/change-cells-mode (:id shape) cell-ids mode)))))

        toggle-edit-mode
        (mf/use-fn
         (mf/deps (:id shape))
         (fn []
           (st/emit! (dw/start-edition-mode (:id shape))
                     (dwge/clear-selection (:id shape)))))]


    (if new-css-system
      [:div {:class (stl/css :grid-cell-menu)}
       [:div {:class (stl/css :grid-cell-menu-title)}
        [:& title-bar {:collapsable? true
                       :collapsed?   (not open?)
                       :on-collapsed #(swap! state* update :open not)
                       :title        "Grid cell"}]]

       (when open?
         [:div {:class (stl/css :grid-cell-menu-container)}
          [:div {:class (stl/css :cell-mode :row)}
           [:& radio-buttons {:selected (d/name cell-mode)
                              :on-change set-cell-mode
                              :name "cell-mode"
                              :wide true}
            [:& radio-button {:value "auto" :id :auto}]
            [:& radio-button {:value "manual" :id :manual}]
            [:& radio-button {:value "area" :id :area}]]]

          (when (= :area cell-mode)
            [:div {:class (stl/css :row)}
             [:input
              {:class (stl/css :area-input)
               :key (dm/str "name-" (:id cell))
               :id "grid-area-name"
               :type "text"
               :aria-label "grid-area-name"
               :placeholder "Area name"
               :default-value area-name
               :auto-complete "off"
               :on-change on-area-name-change}]])

          (when (and (not multiple?) (= :auto cell-mode))
            [:div {:class (stl/css :row)}
             [:div {:class (stl/css :grid-coord-group)}
              [:span {:class (stl/css :icon)} i/layout-rows]
              [:div {:class (stl/css :coord-input)}
               [:> numeric-input*
                {:placeholder "--"
                 :on-click #(dom/select-target %)
                 :on-change (partial on-grid-coordinates :all :column)
                 :value column}]]]

             [:div {:class (stl/css :grid-coord-group)}
              [:span {:class (stl/css :icon)} i/layout-columns]
              [:div {:class (stl/css :coord-input)}
               [:> numeric-input*
                {:placeholder "--"
                 :on-click #(dom/select-target %)
                 :on-change (partial on-grid-coordinates :all :row)
                 :value row}]]]])

          (when (and (not multiple?) (or (= :manual cell-mode) (= :area cell-mode)))
            [:div {:class (stl/css :row)}
             [:div {:class (stl/css :grid-coord-group)}
              [:span {:class (stl/css :icon)} i/layout-rows]
              [:div {:class (stl/css :coord-input)}
               [:> numeric-input*
                {:placeholder "--"
                 :on-pointer-down #(dom/select-target %)
                 :on-change (partial on-grid-coordinates :start :column)
                 :value column}]]
              [:div {:class (stl/css :coord-input)}
               [:> numeric-input*
                {:placeholder "--"
                 :on-pointer-down #(dom/select-target %)
                 :on-change (partial on-grid-coordinates :end :column)
                 :value column-end}]]]

             [:div {:class (stl/css :grid-coord-group)}
              [:span {:class (stl/css :icon)} i/layout-columns]
              [:div {:class (stl/css :coord-input :double)}
               [:> numeric-input*
                {:placeholder "--"
                 :on-pointer-down #(dom/select-target %)
                 :on-change (partial on-grid-coordinates :start :row)
                 :value row}]]
              [:div {:class (stl/css :coord-input)}
               [:> numeric-input*
                {:placeholder "--"
                 :on-pointer-down #(dom/select-target %)
                 :on-change (partial on-grid-coordinates :end :row)
                 :value row-end}]]]])

          [:div {:class (stl/css :row)}
           [:& set-self-alignment {:is-col? false
                                   :alignment align-self
                                   :set-alignment set-alignment}]
           [:& set-self-alignment {:is-col? true
                                   :alignment justify-self
                                   :set-alignment set-justify-self}]]

          [:div {:class (stl/css :row)}
           [:button
            {:class (stl/css :edit-grid-btn)
             :alt    (tr "workspace.layout_grid.editor.options.edit-grid")
             :on-click toggle-edit-mode}
            (tr "workspace.layout_grid.editor.options.edit-grid")]]])]


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
          (when-not multiple?
            [:button.position-btn
             {:on-click #(set-cell-mode :manual)
              :class (dom/classnames :active (= :manual cell-mode))} "Manual"])
          [:button.position-btn
           {:on-click #(set-cell-mode :area)
            :disabled (not valid-area-cells?)
            :class (dom/classnames :active (= :area cell-mode))} "Area"]]]

        [:div.manage-grid-columns
         (when (and (not multiple?) (= :auto cell-mode))
           [:div.grid-auto
            [:div.grid-columns-auto
             [:span.icon i/layout-rows]
             [:div.input-wrapper
              [:> numeric-input*
               {:placeholder "--"
                :on-click #(dom/select-target %)
                :on-change (partial on-grid-coordinates :all :column)
                :value column}]]]
            [:div.grid-rows-auto
             [:span.icon i/layout-columns]
             [:div.input-wrapper
              [:> numeric-input*
               {:placeholder "--"
                :on-click #(dom/select-target %)
                :on-change (partial on-grid-coordinates :all :row)
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

         (when (and (not multiple?) (or (= :manual cell-mode) (= :area cell-mode)))
           [:div.grid-manual
            [:div.grid-columns-auto
             [:span.icon i/layout-rows]
             [:div.input-wrapper
              [:> numeric-input*
               {:placeholder "--"
                :on-pointer-down #(dom/select-target %)
                :on-change (partial on-grid-coordinates :start :column)
                :value column}]
              [:> numeric-input*
               {:placeholder "--"
                :on-pointer-down #(dom/select-target %)
                :on-change (partial on-grid-coordinates :end :column)
                :value column-end}]]]
            [:div.grid-rows-auto
             [:span.icon i/layout-columns]
             [:div.input-wrapper
              [:> numeric-input*
               {:placeholder "--"
                :on-pointer-down #(dom/select-target %)
                :on-change (partial on-grid-coordinates :start :row)
                :value row}]
              [:> numeric-input*
               {:placeholder "--"
                :on-pointer-down #(dom/select-target %)
                :on-change (partial on-grid-coordinates :end :row)
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
                                  :set-alignment set-justify-self}]]]

        [:div.layout-row.single-button
         [:div.btn-wrapper
          [:div.edit-mode
           [:button.tooltip.tooltip-bottom-left
            {:alt    "Grid edit mode"
             :on-click toggle-edit-mode
             :style {:padding 0}}
            "Edit grid"
            i/grid-layout-mode]]]]]])))
