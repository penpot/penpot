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
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
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
  (let [alignment (or alignment :auto)
        type (if is-col? "col" "row")

        handle-set-alignment
        (mf/use-callback
         (mf/deps set-alignment)
         (fn [value]
           (set-alignment (-> value keyword))))]

    [:div {:class (stl/css :self-align-menu)}
     [:& radio-buttons {:selected (d/name alignment)
                        :on-change handle-set-alignment
                        :allow-empty true
                        :name (dm/str "flex-align-items-" type)}
      [:& radio-button {:value "start"
                        :icon  (if is-col?
                                 deprecated-icon/align-self-row-left
                                 deprecated-icon/align-self-column-top)
                        :title "Align self start"
                        :id     (dm/str "align-self-start-" type)}]

      [:& radio-button {:value "center"
                        :icon  (if is-col?
                                 deprecated-icon/align-self-row-center
                                 deprecated-icon/align-self-column-center)
                        :title "Align self center"
                        :id     (dm/str "align-self-center-" type)}]

      [:& radio-button {:value "end"
                        :icon  (if is-col?
                                 deprecated-icon/align-self-row-right
                                 deprecated-icon/align-self-column-bottom)
                        :title "Align self end"
                        :id     (dm/str "align-self-end-" type)}]

      [:& radio-button {:value "stretch"
                        :icon  (if is-col?
                                 deprecated-icon/align-self-row-stretch
                                 deprecated-icon/align-self-column-stretch)
                        :title "Align self stretch"
                        :id     (dm/str "align-self-stretch-" type)}]]]))


(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shape cell cells] :as props}]

  (let [state* (mf/use-state {:open true})
        open?  (:open @state*)

        cells (hooks/use-equal-memo cells)
        cell (or cell (attrs/get-attrs-multi cells cell-props))

        multiple? (= :multiple (:id cell))
        cell-ids (if multiple? (->> cells (map :id)) [(:id cell)])
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


    [:div {:class (stl/css :grid-cell-menu)}
     [:div {:class (stl/css :grid-cell-menu-title)}
      [:> title-bar* {:collapsable  true
                      :collapsed    (not open?)
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
          [:& radio-button {:value "area"
                            :id :area
                            :disabled (not valid-area-cells?)}]]]

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
            [:span {:class (stl/css :icon)} deprecated-icon/flex-vertical]
            [:div {:class (stl/css :coord-input)}
             [:> numeric-input*
              {:placeholder "--"
               :title "Column"
               :on-click #(dom/select-target %)
               :on-change (partial on-grid-coordinates :all :column)
               :value column}]]]

           [:div {:class (stl/css :grid-coord-group)}
            [:span {:class (stl/css :icon)} deprecated-icon/flex-horizontal]
            [:div {:class (stl/css :coord-input)}
             [:> numeric-input*
              {:placeholder "--"
               :title "Row"
               :on-click #(dom/select-target %)
               :on-change (partial on-grid-coordinates :all :row)
               :value row}]]]])

        (when (and (not multiple?) (or (= :manual cell-mode) (= :area cell-mode)))
          [:div {:class (stl/css :row)}
           [:div {:class (stl/css :grid-coord-group)}
            [:span {:class (stl/css :icon)} deprecated-icon/flex-vertical]
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
            [:span {:class (stl/css :icon)} deprecated-icon/flex-horizontal]
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
          (tr "workspace.layout_grid.editor.options.edit-grid")]]])]))
