;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-container
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn dir-icons-refactor
  [val]
  (case val
    :row            i/grid-row-refactor
    :row-reverse    i/row-reverse-refactor
    :column         i/column-refactor
    :column-reverse i/column-reverse-refactor))

(mf/defc direction-btn
  [{:keys [dir saved-dir on-click icon?] :as props}]
  (let [handle-on-click
        (mf/use-fn
         (mf/deps on-click dir)
         (fn []
           (when (some? on-click)
             (on-click dir))))]

    [:button.dir.tooltip.tooltip-bottom
     {:class  (dom/classnames :active         (= saved-dir dir)
                              :row            (= :row dir)
                              :row-reverse    (= :row-reverse dir)
                              :column-reverse (= :column-reverse dir)
                              :column         (= :column dir))
      :key    (dm/str  "direction-" dir)
      :alt    (str/replace (str/capital (d/name dir)) "-" " ")
      :on-click handle-on-click}
     (if icon?
       i/auto-direction
       (str/replace (str/capital (d/name dir)) "-" " "))]))


;; FLEX COMPONENTS

(def layout-container-flex-attrs
  [:layout                 ;; :flex, :grid in the future
   :layout-flex-dir        ;; :row, :row-reverse, :column, :column-reverse
   :layout-gap-type        ;; :simple, :multiple
   :layout-gap             ;; {:row-gap number , :column-gap number}

   :layout-align-items     ;; :start :end :center :stretch
   :layout-justify-content ;; :start :center :end :space-between :space-around :space-evenly
   :layout-align-content   ;; :start :center :end :space-between :space-around :space-evenly :stretch (by default)
   :layout-wrap-type       ;; :wrap, :nowrap
   :layout-padding-type    ;; :simple, :multiple
   :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative

   :layout-grid-dir ;; :row :column
   :layout-justify-items
   :layout-grid-columns
   :layout-grid-rows])

(defn get-layout-flex-icon
  [type val is-col?]
  (case type
    :align-items
    (if is-col?
      (case val
        :start    i/align-items-column-start
        :end      i/align-items-column-end
        :center   i/align-items-column-center
        :stretch  i/align-items-column-strech
        :baseline i/align-items-column-baseline)
      (case val
        :start    i/align-items-row-start
        :end      i/align-items-row-end
        :center   i/align-items-row-center
        :stretch  i/align-items-row-strech
        :baseline i/align-items-row-baseline))

    :justify-content
    (if is-col?
      (case val
        :start         i/justify-content-column-start
        :end           i/justify-content-column-end
        :center        i/justify-content-column-center
        :space-around  i/justify-content-column-around
        :space-evenly  i/justify-content-column-evenly
        :space-between i/justify-content-column-between)
      (case val
        :start         i/justify-content-row-start
        :end           i/justify-content-row-end
        :center        i/justify-content-row-center
        :space-around  i/justify-content-row-around
        :space-evenly  i/justify-content-row-evenly
        :space-between i/justify-content-row-between))

    :align-content
    (if is-col?
      (case val
        :start         i/align-content-column-start
        :end           i/align-content-column-end
        :center        i/align-content-column-center
        :space-around  i/align-content-column-around
        :space-evenly  i/align-content-column-evenly
        :space-between i/align-content-column-between
        :stretch nil)

      (case val
        :start         i/align-content-row-start
        :end           i/align-content-row-end
        :center        i/align-content-row-center
        :space-around  i/align-content-row-around
        :space-evenly  i/align-content-row-evenly
        :space-between i/align-content-row-between
        :stretch nil))

    (case val
      :start         i/align-content-row-start
      :end           i/align-content-row-end
      :center        i/align-content-row-center
      :space-around  i/align-content-row-around
      :space-between i/align-content-row-between
      :stretch nil)

    :align-self
    (if is-col?
      (case val
        :auto     i/minus
        :start    i/align-self-row-left
        :end      i/align-self-row-right
        :center   i/align-self-row-center
        :stretch  i/align-self-row-strech
        :baseline i/align-self-row-baseline)
      (case val
        :auto     i/minus
        :start    i/align-self-column-top
        :end      i/align-self-column-bottom
        :center   i/align-self-column-center
        :stretch  i/align-self-column-strech
        :baseline i/align-self-column-baseline))))

(defn get-layout-flex-icon-refactor
  [type val is-col?]
  (case type
    :align-items
    (if is-col?
      (case val
        :start    i/align-items-column-start-refactor
        :end      i/align-items-column-end-refactor
        :center   i/align-items-column-center-refactor
        :stretch  i/align-items-column-strech
        :baseline i/align-items-column-baseline)
      (case val ;; TODO Check strech and baseline icons
        :start    i/align-items-row-start-refactor
        :end      i/align-items-row-end-refactor
        :center   i/align-items-row-center-refactor
        :stretch  i/align-items-row-strech
        :baseline i/align-items-row-baseline))

    :justify-content
    (if is-col?
      (case val
        :start         i/justify-content-column-start-refactor
        :end           i/justify-content-column-end-refactor
        :center        i/justify-content-column-center-refactor
        :space-around  i/justify-content-column-around-refactor
        :space-evenly  i/justify-content-column-evenly-refactor
        :space-between i/justify-content-column-between-refactor)
      (case val
        :start         i/justify-content-row-start-refactor
        :end           i/justify-content-row-end-refactor
        :center        i/justify-content-row-center-refactor
        :space-around  i/justify-content-row-around-refactor
        :space-evenly  i/justify-content-row-evenly-refactor
        :space-between i/justify-content-row-between-refactor))

    :align-content
    (if is-col?
      (case val
        :start         i/align-content-column-start-refactor
        :end           i/align-content-column-end-refactor
        :center        i/align-content-column-center-refactor
        :space-around  i/align-content-column-around-refactor
        :space-evenly  i/align-content-column-evenly-refactor
        :space-between i/align-content-column-between-refactor
        :stretch nil)

      (case val
        :start         i/align-content-row-start-refactor
        :end           i/align-content-row-end-refactor
        :center        i/align-content-row-center-refactor
        :space-around  i/align-content-row-around-refactor
        :space-evenly  i/align-content-row-evenly-refactor
        :space-between i/align-content-row-between-refactor
        :stretch nil))


    :align-self
    (if is-col?
      (case val
        :auto     i/remove-refactor
        :start    i/align-self-row-left-refactor
        :end      i/align-self-row-right-refactor
        :center   i/align-self-row-center-refactor
        :stretch  i/align-self-row-strech
        :baseline i/align-self-row-baseline)
      (case val
        :auto     i/remove-refactor
        :start    i/align-self-column-top-refactor
        :end      i/align-self-column-bottom-refactor
        :center   i/align-self-column-center-refactor
        :stretch  i/align-self-column-strech
        :baseline i/align-self-column-baseline))))

(mf/defc direction-row-flex
  [{:keys [saved-dir on-change] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:& radio-buttons {:selected (d/name saved-dir)
                         :on-change on-change
                         :name "flex-direction"}
       [:& radio-button {:value "row"
                         :id "flex-direction-row"
                         :title "Row"
                         :icon (dir-icons-refactor :row)}]
       [:& radio-button {:value "row-reverse"
                         :id "flex-direction-row-reverse"
                         :title "Row reverse"
                         :icon (dir-icons-refactor :row-reverse)}]
       [:& radio-button {:value "column"
                         :id "flex-direction-column"
                         :title "Column"
                         :icon (dir-icons-refactor :column)}]
       [:& radio-button {:value "column-reverse"
                         :id "flex-direction-column-reverse"
                         :title "Column reverse"
                         :icon (dir-icons-refactor :column-reverse)}]]
      [:*
       (for [dir [:row :row-reverse :column :column-reverse]]
         [:& direction-btn {:key (d/name dir)
                            :dir dir
                            :saved-dir saved-dir
                            :on-click on-change
                            :icon? true}])])))

(mf/defc wrap-row
  [{:keys [wrap-type on-click] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:button {:class (stl/css-case :wrap-button true
                                     :selected (= wrap-type :wrap))
                :title (if (= :wrap wrap-type)
                         "No wrap"
                         "Wrap")
                :on-click on-click}
       i/wrap-refactor]

      [:*
       [:button.tooltip.tooltip-bottom
        {:class  (dom/classnames :active  (= wrap-type :nowrap))
         :alt    "No wrap"
         :data-value :nowrap
         :on-click on-click
         :style {:padding 0}}
        [:span.no-wrap i/minus]]
       [:button.wrap.tooltip.tooltip-bottom
        {:class  (dom/classnames :active  (= wrap-type :wrap))
         :alt    "Wrap"
         :data-value :wrap
         :on-click on-click}
        i/auto-wrap]])))

(mf/defc align-row
  [{:keys [is-col? align-items on-change] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:& radio-buttons {:selected (d/name align-items)
                         :on-change on-change
                         :name "flex-align-items"}
       [:& radio-button {:value "start"
                         :icon  (get-layout-flex-icon-refactor :align-items :start is-col?)
                         :title "Align items start"
                         :id     "align-items-start"}]
       [:& radio-button {:value "center"
                         :icon  (get-layout-flex-icon-refactor :align-items :center is-col?)
                         :title "Align items center"
                         :id    "align-items-center"}]
       [:& radio-button {:value "end"
                         :icon  (get-layout-flex-icon-refactor :align-items :end is-col?)
                         :title "Align items end"
                         :id    "align-items-end"}]]

      [:div.align-items-style
        [:button.align-start.tooltip.tooltip-bottom
        {:class    (dom/classnames :active  (= align-items :start))
         :alt      "Align items start"
         :data-value :start
         :on-click on-change}
        (get-layout-flex-icon :align-items :start is-col?)]
       [:button.align-start.tooltip.tooltip-bottom-left
        {:class    (dom/classnames :active  (= align-items :center))
         :alt      "Align items center"
         :data-value :center
         :on-click on-change}
        (get-layout-flex-icon :align-items :center is-col?)]
       [:button.align-start.tooltip.tooltip-bottom-left
        {:class    (dom/classnames :active  (= align-items :end))
         :alt      "Align items end"
         :data-value :end
         :on-click on-change}
        (get-layout-flex-icon :align-items :end is-col?)]])))

(mf/defc align-content-row
  [{:keys [is-col? align-content on-change] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:& radio-buttons {:selected (d/name align-content)
                         :on-change on-change
                         :name "flex-align-content"}
       [:& radio-button {:value      "start"
                         :icon       (get-layout-flex-icon-refactor :align-content :start is-col?)
                         :title      "Align content start"
                         :id         "align-content-start"}]
       [:& radio-button {:value      "center"
                         :icon       (get-layout-flex-icon-refactor :align-content :center is-col?)
                         :title      "Align content center"
                         :id         "align-content-center"}]
       [:& radio-button {:value      "end"
                         :icon       (get-layout-flex-icon-refactor :align-content :end is-col?)
                         :title      "Align content end"
                         :id         "align-content-end"}]
       [:& radio-button {:value      "space-between"
                         :icon       (get-layout-flex-icon-refactor :align-content :space-between is-col?)
                         :title      "Align content space-between"
                         :id         "align-content-space-between"}]
       [:& radio-button {:value      "space-around"
                         :icon       (get-layout-flex-icon-refactor :align-content :space-around is-col?)
                         :title      "Align content space-around"
                         :id         "align-content-space-around"}]
       [:& radio-button {:value      "space-evenly"
                         :icon       (get-layout-flex-icon-refactor :align-content :space-evenly is-col?)
                         :title      "Align content space-evenly"
                         :id         "align-content-space-evenly"}]]
      [:*
       [:div.align-content-style
        [:button.align-content.tooltip.tooltip-bottom
         {:class    (dom/classnames :active  (= align-content :start))
          :alt      "Align content start"
          :data-value :start
          :on-click on-change}
         (get-layout-flex-icon :align-content :start is-col?)]
        [:button.align-content.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= align-content :center))
          :alt      "Align content center"
          :data-value :center
          :on-click on-change}
         (get-layout-flex-icon :align-content :center is-col?)]
        [:button.align-content.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= align-content :end))
          :alt      "Align content end"
          :data-value :end
          :on-click on-change}
         (get-layout-flex-icon :align-content :end is-col?)]]
       [:div.align-content-style
        [:button.align-content.tooltip.tooltip-bottom
         {:class    (dom/classnames :active  (= align-content :space-between))
          :alt      "Align content space-between"
          :data-value :space-between
          :on-click on-change}
         (get-layout-flex-icon :align-content :space-between is-col?)]
        [:button.align-content.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= align-content :space-around))
          :alt      "Align content space-around"
          :data-value :space-around
          :on-click on-change}
         (get-layout-flex-icon :align-content :space-around is-col?)]
        [:button.align-content.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= align-content :space-evenly))
          :alt      "Align content space-evenly"
          :data-value :space-evenly
          :on-click on-change}
         (get-layout-flex-icon :align-content :space-evenly is-col?)]]])))

(mf/defc justify-content-row
  [{:keys [is-col? justify-content on-change] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:& radio-buttons {:selected (d/name justify-content)
                         :on-change on-change
                         :name "flex-justify"}
       [:& radio-button {:value "start"
                         :icon  (get-layout-flex-icon-refactor :justify-content :start is-col?)
                         :title "Justify content start"
                         :id    "justify-content-start"}]
       [:& radio-button {:value "center"
                         :icon  (get-layout-flex-icon-refactor :justify-content :center is-col?)
                         :title "Justify content center"
                         :id    "justify-content-center"}]
       [:& radio-button {:value "end"
                         :icon  (get-layout-flex-icon-refactor :justify-content :end is-col?)
                         :title "Justify content end"
                         :id    "justify-content-end"}]
       [:& radio-button {:value "space-between"
                         :icon  (get-layout-flex-icon-refactor :justify-content :space-between is-col?)
                         :title "Justify content space-between"
                         :id    "justify-content-space-between"}]
       [:& radio-button {:value "space-around"
                         :icon  (get-layout-flex-icon-refactor :justify-content :space-around is-col?)
                         :title "Justify content space-around"
                         :id    "justify-content-space-around"}]
       [:& radio-button {:value "space-evenly"
                         :icon  (get-layout-flex-icon-refactor :justify-content :space-evenly is-col?)
                         :title "Justify content space-evenly"
                         :id    "justify-content-space-evenly"}]]
      [:*
       [:div.justify-content-style
        [:button.justify.tooltip.tooltip-bottom
         {:class    (dom/classnames :active  (= justify-content :start))
          :alt      "Justify content start"
          :data-value :start
          :on-click on-change}
         (get-layout-flex-icon :justify-content :start is-col?)]
        [:button.justify.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= justify-content :center))
          :data-value :center
          :alt      "Justify content center"
          :on-click on-change}
         (get-layout-flex-icon :justify-content :center is-col?)]
        [:button.justify.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= justify-content :end))
          :alt      "Justify content end"
          :data-value :end
          :on-click on-change}
         (get-layout-flex-icon :justify-content :end is-col?)]]
       [:div.justify-content-style
        [:button.justify.tooltip.tooltip-bottom
         {:class    (dom/classnames :active  (= justify-content :space-between))
          :alt      "Justify content space-between"
          :data-value :space-between
          :on-click on-change}
         (get-layout-flex-icon :justify-content :space-between is-col?)]
        [:button.justify.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= justify-content :space-around))
          :alt      "Justify content space-around"
          :data-value :space-around
          :on-click on-change}
         (get-layout-flex-icon :justify-content :space-around is-col?)]
        [:button.justify.tooltip.tooltip-bottom-left
         {:class    (dom/classnames :active  (= justify-content :space-evenly))
          :alt      "Justify content space-evenly"
          :data-value :space-evenly
          :on-click on-change}
         (get-layout-flex-icon :justify-content :space-evenly is-col?)]]])))

(mf/defc padding-section
  [{:keys [values on-change-style on-change] :as props}]

  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        padding-type (:layout-padding-type values)

        toggle-padding-mode
        (mf/use-fn
         (mf/deps padding-type on-change-style)
         (fn []
           (let [padding (if (= padding-type :multiple) :simple :multiple)]
             (on-change-style padding))))

        p1 (if (and (not (= :multiple (:layout-padding values)))
                    (= (dm/get-in values [:layout-padding :p1])
                       (dm/get-in values [:layout-padding :p3])))
             (dm/get-in values [:layout-padding :p1])
             "--")

        p2 (if (and (not (= :multiple (:layout-padding values)))
                    (= (dm/get-in values [:layout-padding :p2])
                       (dm/get-in values [:layout-padding :p4])))
             (dm/get-in values [:layout-padding :p2])
             "--")

        select-paddings
        (fn [p1? p2? p3? p4?]
          (st/emit! (udw/set-paddings-selected {:p1 p1? :p2 p2? :p3 p3? :p4 p4?})))

        select-padding #(select-paddings (= % :p1) (= % :p2) (= % :p3) (= % :p4))]

    (mf/use-effect
     (fn []
       (fn []
         ;;on destroy component
         (select-paddings false false false false))))

    (if new-css-system
      [:div {:class (stl/css :padding-group)}
       [:div {:class (stl/css :padding-inputs)}
        (cond
          (= padding-type :simple)
          [:div {:class (stl/css :paddings-simple)}
           [:div {:class (stl/css :padding-simple)
                  :title "Vertical padding"}
            [:span {:class (stl/css :icon)}
             i/padding-top-bottom-refactor]
            [:> numeric-input*
             {:className (stl/css :numeric-input)
              :placeholder "--"
              :on-change (partial on-change :simple :p1)
              :on-focus #(do
                           (dom/select-target %)
                           (select-paddings true false true false))
              :nillable true
              :min 0
              :value p1}]]
           [:div {:class (stl/css :padding-simple)
                  :title "Horizontal padding"}

            [:span {:class (stl/css :icon)}
             i/padding-left-right-refactor]
            [:> numeric-input*
             {:className (stl/css :numeric-input)
              :placeholder "--"
              :on-change (partial on-change :simple :p2)
              :on-focus #(do (dom/select-target %)
                             (select-paddings false true false true))
              :on-blur #(select-paddings false false false false)
              :nillable true
              :min 0
              :value p2}]]]
          (= padding-type :multiple)
          [:div {:class (stl/css :paddings-multiple)}

           [:div {:class (stl/css :padding-multiple)
                  :title "Top padding"}
            [:span {:class (stl/css :icon)}
             i/padding-top-refactor]
            [:> numeric-input*
             {:className (stl/css :numeric-input)
              :placeholder "--"
              :on-change (partial on-change :multiple :p1)
              :on-focus #(do (dom/select-target %)
                             (select-padding :p1))
              :on-blur #(select-paddings false false false false)
              :nillable true
              :min 0
              :value  (:p1 (:layout-padding values))}]]

           [:div {:class (stl/css :padding-multiple)
                  :title "Right padding"}
            [:span {:class (stl/css :icon)}
             i/padding-right-refactor]
            [:> numeric-input*
             {:className (stl/css :numeric-input)
              :placeholder "--"
              :on-change (partial on-change :multiple :p2)
              :on-focus #(do (dom/select-target %)
                             (select-padding :p2))
              :on-blur #(select-paddings false false false false)
              :nillable true
              :min 0
              :value  (:p2 (:layout-padding values))}]]

           [:div {:class (stl/css :padding-multiple)
                  :title "Bottom padding"}
            [:span {:class (stl/css :icon)}
             i/padding-bottom-refactor]
            [:> numeric-input*
             {:className (stl/css :numeric-input)
              :placeholder "--"
              :on-change (partial on-change :multiple :p3)
              :on-focus #(do (dom/select-target %)
                             (select-padding :p3))
              :on-blur #(select-paddings false false false false)
              :nillable true
              :min 0
              :value  (:p3 (:layout-padding values))}]]

           [:div {:class (stl/css :padding-multiple)
                  :title "Left padding"}
            [:span {:class (stl/css :icon)}
             i/padding-left-refactor]
            [:> numeric-input*
             {:className (stl/css :numeric-input)
              :placeholder "--"
              :on-change (partial on-change :multiple :p4)
              :on-focus #(do (dom/select-target %)
                             (select-padding :p4))
              :on-blur #(select-paddings false false false false)
              :nillable true
              :min 0
              :value  (:p4 (:layout-padding values))}]]])]
       [:button {:class (stl/css-case :padding-toggle true
                                      :selected (= padding-type :multiple))
                 :on-click toggle-padding-mode}
        i/padding-extended-refactor]]

      [:div.padding-row
       (cond
         (= padding-type :simple)

         [:div.padding-group
          [:div.padding-item.tooltip.tooltip-bottom-left
           {:alt "Vertical padding"}
           [:span.icon.rotated i/auto-padding-both-sides]
           [:> numeric-input*
            {:placeholder "--"
             :on-change (partial on-change :simple :p1)
             :on-focus #(do
                          (dom/select-target %)
                          (select-paddings true false true false))
             :nillable true
             :min 0
             :value p1}]]

          [:div.padding-item.tooltip.tooltip-bottom-left
           {:alt "Horizontal padding"}
           [:span.icon i/auto-padding-both-sides]
           [:> numeric-input*
            {:placeholder "--"
             :on-change (partial on-change :simple :p2)
             :on-focus #(do (dom/select-target %)
                            (select-paddings false true false true))
             :on-blur #(select-paddings false false false false)
             :nillable true
             :min 0
             :value p2}]]]

         (= padding-type :multiple)
         [:div.wrapper
          (for [num [:p1 :p2 :p3 :p4]]
            [:div.tooltip.tooltip-bottom
             {:key (dm/str "padding-" (d/name num))
              :alt (case num
                     :p1 "Top"
                     :p2 "Right"
                     :p3 "Bottom"
                     :p4 "Left")}
             [:div.input-element.auto
              [:> numeric-input*
               {:placeholder "--"
                :on-change (partial on-change :multiple num)
                :on-focus #(do (dom/select-target %)
                               (select-padding num))
                :on-blur #(select-paddings false false false false)
                :nillable true
                :min 0
                :value (num (:layout-padding values))}]]])])

       [:div.padding-icons
        [:div.padding-icon.tooltip.tooltip-bottom-left
         {:class (dom/classnames :selected (= padding-type :multiple))
          :alt "Independent paddings"
          :on-click toggle-padding-mode}
         i/auto-padding-side]]])))

(mf/defc gap-section
  [{:keys [is-col? wrap-type gap-selected? on-change gap-value]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        select-gap
        (fn [gap]
          (st/emit! (udw/set-gap-selected gap)))]

    (mf/use-effect
     (fn []
       (fn []
         ;;on destroy component
         (select-gap nil))))

    (if new-css-system
      [:div {:class (stl/css :gap-group)}
       [:div {:class (stl/css-case :row-gap true
                                   :disabled (and (= :nowrap wrap-type) (not is-col?)))
              :title "Row gap"}
        [:span {:class (stl/css :icon)}
         i/gap-vertical-refactor]
        [:> numeric-input* {:className (stl/css :numeric-input true)
                            :no-validate true
                            :placeholder "--"
                            :on-focus (fn [event]
                                        (select-gap :row-gap)
                                        (reset! gap-selected? :row-gap)
                                        (dom/select-target event))
                            :on-change (partial on-change (= :nowrap wrap-type) :row-gap)
                            :on-blur (fn [_]
                                       (select-gap nil)
                                       (reset! gap-selected? :none))
                            :nillable true
                            :min 0
                            :value (:row-gap gap-value)
                            :disabled (and (= :nowrap wrap-type) (not is-col?))}]]
       [:div {:class (stl/css-case :column-gap true
                                   :disabled (and (= :nowrap wrap-type) is-col?))
              :title "Column gap"}
        [:span {:class (stl/css :icon)}
         i/gap-horizontal-refactor]
        [:> numeric-input* {:className (stl/css :numeric-input)
                            :no-validate true
                            :placeholder "--"
                            :on-focus (fn [event]
                                        (select-gap :column-gap)
                                        (reset! gap-selected? :column-gap)
                                        (dom/select-target event))
                            :on-change (partial on-change (= :nowrap wrap-type) :column-gap)
                            :on-blur (fn [_]
                                       (select-gap nil)
                                       (reset! gap-selected? :none))
                            :nillable true
                            :min 0
                            :value (:column-gap gap-value)
                            :disabled (and (= :nowrap wrap-type) is-col?)}]]]

      [:div.layout-row
       [:div.gap.row-title "Gap"]
       [:div.gap-group
        [:div.gap-row.tooltip.tooltip-bottom-left
         {:alt "Column gap"}
         [:span.icon
          i/auto-gap]
         [:> numeric-input* {:no-validate true
                             :placeholder "--"
                             :on-focus (fn [event]
                                         (select-gap :column-gap)
                                         (reset! gap-selected? :column-gap)
                                         (dom/select-target event))
                             :on-change (partial on-change (= :nowrap wrap-type) :column-gap)
                             :on-blur (fn [_]
                                        (select-gap nil)
                                        (reset! gap-selected? :none))
                             :nillable true
                             :min 0
                             :value (:column-gap gap-value)
                             :disabled (and (= :nowrap wrap-type) is-col?)}]]

        [:div.gap-row.tooltip.tooltip-bottom-left
         {:alt "Row gap"}
         [:span.icon.rotated
          i/auto-gap]
         [:> numeric-input* {:no-validate true
                             :placeholder "--"
                             :on-focus (fn [event]
                                         (select-gap :row-gap)
                                         (reset! gap-selected? :row-gap)
                                         (dom/select-target event))
                             :on-change (partial on-change (= :nowrap wrap-type) :row-gap)
                             :on-blur (fn [_]
                                        (select-gap nil)
                                        (reset! gap-selected? :none))
                             :nillable true
                             :min 0
                             :value (:row-gap gap-value)
                             :disabled (and (= :nowrap wrap-type) (not is-col?))}]]]])))

;; GRID COMPONENTS

(defn get-layout-grid-icon
  [type val is-col?]
  (case type
    :justify-items
    (if is-col?
      (case val
        :stretch       i/align-items-row-strech
        :start         i/grid-justify-content-column-start
        :end           i/grid-justify-content-column-end
        :center        i/grid-justify-content-column-center
        :space-around  i/grid-justify-content-column-around
        :space-between i/grid-justify-content-column-between
        :space-evenly  i/grid-justify-content-column-between)

      (case val
        :stretch       i/align-items-column-strech
        :start         i/grid-justify-content-row-start
        :end           i/grid-justify-content-row-end
        :center        i/grid-justify-content-row-center
        :space-around  i/grid-justify-content-row-around
        :space-between i/grid-justify-content-row-between
        :space-evenly  i/grid-justify-content-row-between))))

(mf/defc direction-row-grid
  [{:keys [saved-dir on-change-refactor on-click] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:& radio-buttons {:selected (d/name saved-dir)
                         :on-change on-change-refactor
                         :name "grid-direction"}
       [:& radio-button {:value "row"
                         :id "grid-direction-row"
                         :title "Row"
                         :icon (dir-icons-refactor :row)}]
       [:& radio-button {:value "column"
                         :id "grid-direction-column"
                         :title "Column"
                         :icon (dir-icons-refactor :column)}]]
      [:*
       [:& direction-btn {:key "grid-direction-row"
                          :dir :row
                          :saved-dir saved-dir
                          :on-click on-click
                          :icon? true}]
       [:& direction-btn {:key "grid-direction-column"
                          :dir :column
                          :saved-dir saved-dir
                          :on-click on-click
                          :icon? true}]])))

(mf/defc grid-edit-mode
  [{:keys [id] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        edition (mf/deref refs/selected-edition)
        active? (= id edition)

        toggle-edit-mode
        (mf/use-fn
         (mf/deps id edition)
         (fn []
           (if-not active?
             (st/emit! (udw/start-edition-mode id))
             (st/emit! :interrupt))))]
    (if new-css-system
      [:div "new-edit-mode"]
      [:button.tooltip.tooltip-bottom-left
       {:class  (dom/classnames :active  active?)
        :alt    "Grid edit mode"
        :on-click #(toggle-edit-mode)
        :style {:padding 0}}
       "Edit grid"
       i/grid-layout-mode])))

(mf/defc align-grid-row
  [{:keys [is-col? align-items set-align] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        type (if is-col? :column :row)]
    (if new-css-system
      [:& radio-buttons {:selected (d/name align-items)
                         :on-change set-align
                         :name "flex-align-items"}
       [:& radio-button {:value "start"
                         :icon  (get-layout-flex-icon-refactor :align-items :start is-col?)
                         :title "Align items start"
                         :id     "align-items-start"}]
       [:& radio-button {:value "center"
                         :icon  (get-layout-flex-icon-refactor :align-items :center is-col?)
                         :title "Align items center"
                         :id    "align-items-center"}]
       [:& radio-button {:value "end"
                         :icon  (get-layout-flex-icon-refactor :align-items :end is-col?)
                         :title "Align items end"
                         :id    "align-items-end"}]]
      [:div.align-items-style
       (for [align [:start :center :end]]
         [:button.align-start.tooltip
          {:class    (dom/classnames :active  (= align-items align)
                                     :tooltip-bottom-left (not= align :start)
                                     :tooltip-bottom (= align :start))
           :alt      (if is-col?
                       (dm/str "justify-items: " (d/name align))
                       (dm/str "align-items: " (d/name align)))
           :on-click #(set-align align type)
           :key      (dm/str "align-items" (d/name align))}
          (get-layout-flex-icon :align-items align is-col?)])])))

(mf/defc justify-grid-row
  [{:keys [is-col? justify-items set-justify] :as props}]
  (let [type (if is-col? :column :row)]
    [:div.justify-content-style
     (for [align [:start :center :end :space-around :space-between :stretch]]
       [:button.align-start.tooltip
        {:class    (dom/classnames :active  (= justify-items align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (if is-col?
                     (dm/str "align-content: " (d/name align))
                     (dm/str "justify-content: " (d/name align)))
         :on-click #(set-justify align type)
         :key      (dm/str "justify-content" (d/name align))}
        (get-layout-grid-icon :justify-items align is-col?)])]))

(defn manage-values [{:keys [value type]}]
  (case type
    :auto "auto"
    :percent (dm/str value "%")
    :flex (dm/str value "fr")
    :fixed (dm/str value "px")
    value))

(mf/defc grid-track-info
  [{:keys [is-col? type index column set-column-value set-column-type remove-element reorder-track hover-track]}]
  (let [drop-track
        (mf/use-fn
         (mf/deps type reorder-track index)
         (fn [drop-position data]
           (reorder-track type (:index data) (if (= :top drop-position) (dec index) index))))

        pointer-enter
        (mf/use-fn
         (mf/deps type hover-track index)
         (fn []
           (hover-track type index true)))

        pointer-leave
        (mf/use-fn
         (mf/deps type hover-track index)
         (fn []
           (hover-track type index false)))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/grid-track"
         :on-drop drop-track
         :data {:is-col? is-col?
                :index index
                :column column}
         :draggable? true)]
    [:div.column-info
     {:ref dref
      :class (dom/classnames
              :dnd-over-top (or (= (:over dprops) :top)
                                (= (:over dprops) :center))
              :dnd-over-bot (= (:over dprops) :bot))
      :on-pointer-enter pointer-enter
      :on-pointer-leave pointer-leave}
     [:div.direction-grid-icon
      (if is-col?
        i/layout-rows
        i/layout-columns)]

     [:div.grid-column-value
      [:> numeric-input* {:no-validate true
                          :value (:value column)
                          :on-change #(set-column-value type index %)
                          :placeholder "--"
                          :disabled (= :auto (:type column))}]]
     [:div.grid-column-unit
      [:& select
       {:class "grid-column-unit-selector"
        :default-value (:type column)
        :options [{:value :flex :label "FR"}
                  {:value :auto :label "AUTO"}
                  {:value :fixed :label "PX"}
                  {:value :percent :label "%"}]
        :on-change #(set-column-type type index %)}]]
     [:button.remove-grid-column
      {:on-click #(remove-element type index)}
      i/minus]]))

(mf/defc grid-columns-row
  [{:keys [is-col? expanded? column-values toggle add-new-element set-column-value set-column-type remove-element reorder-track hover-track] :as props}]
  (let [column-num (count column-values)
        direction (if (> column-num 1)
                    (if is-col? "Columns " "Rows ")
                    (if is-col? "Column " "Row "))

        column-vals (str/join ", " (map manage-values column-values))
        generated-name (dm/str direction  (if (= column-num 0) " - empty" (dm/str column-num " (" column-vals ")")))
        type (if is-col? :column :row)]

    [:div.grid-columns
     [:div.grid-columns-header
      [:button.expand-icon
       {:on-click toggle} i/actions]

      [:div.columns-info {:title generated-name
                          :on-click toggle} generated-name]
      [:button.add-column {:on-click #(do
                                        (when-not expanded? (toggle))
                                        (add-new-element type ctl/default-track-value))} i/plus]]

     (when expanded?
       [:& h/sortable-container {}
        [:div.columns-info-wrapper
         (for [[index column] (d/enumerate column-values)]
           [:& grid-track-info {:key (dm/str index "-" (name type) "-" column)
                                :type type
                                :is-col? is-col?
                                :index index
                                :column column
                                :set-column-value set-column-value
                                :set-column-type set-column-type
                                :remove-element remove-element
                                :reorder-track reorder-track
                                :hover-track hover-track}])]])]))

;; LAYOUT COMPONENT

(mf/defc layout-container-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "multiple"]))]}
  [{:keys [ids values multiple] :as props}]
  (let [new-css-system      (mf/use-ctx ctx/new-css-system)

        ;; Display
        layout-type         (:layout values)
        has-layout?         (some? layout-type)

        state*              (mf/use-state (if layout-type
                                            true
                                            false))

        open? (deref state*)
        toggle-content (mf/use-fn #(swap! state* not))

        on-add-layout
        (fn [type]
          (st/emit! (dwsl/create-layout type))
          (reset! state* true))

        on-set-layout
        (mf/use-fn
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (on-add-layout value))))

        on-remove-layout
        (fn [_]
          (st/emit! (dwsl/remove-layout ids))
          (reset! state* false))

        set-flex
        (fn []
          (st/emit! (dwsl/remove-layout ids))
          (on-add-layout :flex))

        set-grid
        (fn []
          (st/emit! (dwsl/remove-layout ids))
          (on-add-layout :grid))

        toggle-layout-style
        (mf/use-fn
         (fn [value]
           (if (= "flex" value)
             (set-flex)
             (set-grid))))

        ;; Flex-direction

        saved-dir (:layout-flex-dir values)

        is-col?   (or (= :column saved-dir) (= :column-reverse saved-dir))

        set-direction-refactor
        (mf/use-fn
         (mf/deps [layout-type ids])
         (fn [dir]
           (let [dir (if new-css-system (keyword dir) dir)]
             (if (= :flex layout-type)
               (st/emit! (dwsl/update-layout ids {:layout-flex-dir dir}))
               (st/emit! (dwsl/update-layout ids {:layout-grid-dir dir}))))))

        ;; Wrap type

        wrap-type (:layout-wrap-type values)

        toggle-wrap-refactor
        (mf/use-fn
         (mf/deps [wrap-type ids])
         (fn []
           (let [type (if (= wrap-type :wrap)
                        :nowrap
                        :wrap)]
             (st/emit! (dwsl/update-layout ids {:layout-wrap-type type})))))

        set-wrap
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [type (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (keyword))]
             (st/emit! (dwsl/update-layout ids {:layout-wrap-type type})))))


        ;; Align items

        align-items         (:layout-align-items values)

        set-align-items
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (st/emit! (dwsl/update-layout ids {:layout-align-items value})))))

        set-align-items-refactor
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-align-items (keyword value)}))))

        ;; Justify content

        justify-content     (:layout-justify-content values)

        set-justify-content
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (st/emit! (dwsl/update-layout ids {:layout-justify-content value})))))

        set-justify-content-refactor
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-justify-content (keyword value)}))))

        ;; Align content

        align-content         (:layout-align-content values)

        set-align-content
        (mf/use-fn
         (mf/deps ids align-content)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (if (= align-content value)
               (st/emit! (dwsl/update-layout ids {:layout-align-content :stretch}))
               (st/emit! (dwsl/update-layout ids {:layout-align-content value}))))))

        set-align-content-refactor
        (mf/use-fn
         (mf/deps ids)
         (fn [value align-content]
           (if (= align-content value)
             (st/emit! (dwsl/update-layout ids {:layout-align-content :stretch}))
             (st/emit! (dwsl/update-layout ids {:layout-align-content (keyword value)})))))


        ;; Gap

        gap-selected?       (mf/use-state :none)

        set-gap
        (fn [gap-multiple? type val]
          (let [val (mth/finite val 0)]
            (if gap-multiple?
              (st/emit! (dwsl/update-layout ids {:layout-gap {:row-gap val :column-gap val}}))
              (st/emit! (dwsl/update-layout ids {:layout-gap {type val}})))))

        ;; Padding

        change-padding-type
        (fn [type]
          (st/emit! (dwsl/update-layout ids {:layout-padding-type type})))

        on-padding-change
        (mf/use-fn
         (mf/deps ids)
         (fn [type prop val]
          (let [val (mth/finite val 0)]
            (cond
              (and (= type :simple) (= prop :p1))
              (st/emit! (dwsl/update-layout ids {:layout-padding {:p1 val :p3 val}}))

              (and (= type :simple) (= prop :p2))
              (st/emit! (dwsl/update-layout ids {:layout-padding {:p2 val :p4 val}}))

              :else
              (st/emit! (dwsl/update-layout ids {:layout-padding {prop val}}))))))

        ;; Grid-direction

        saved-grid-dir (:layout-grid-dir values)

        set-direction
        (mf/use-fn
         (mf/deps [layout-type ids new-css-system])
         (fn [dir]
           (let [dir (if new-css-system (keyword dir) dir)]
             (if (= :flex layout-type)
               (st/emit! (dwsl/update-layout ids {:layout-flex-dir dir}))
               (st/emit! (dwsl/update-layout ids {:layout-grid-dir dir}))))))

        ;; Align grid
        align-items-row    (:layout-align-items values)
        align-items-column (:layout-justify-items values)

        set-align-grid
        (fn [value type]
          (if (= type :row)
            (st/emit! (dwsl/update-layout ids {:layout-align-items value}))
            (st/emit! (dwsl/update-layout ids {:layout-justify-items value}))))

        ;; Justify grid
        grid-justify-content-row    (:layout-justify-content values)
        grid-justify-content-column (:layout-align-content values)

        grid-enabled?  (features/use-feature "layout/grid")

        set-justify-grid
        (mf/use-fn
         (mf/deps ids)
         (fn [value type]
           (if (= type :row)
             (st/emit! (dwsl/update-layout ids {:layout-justify-content value}))
             (st/emit! (dwsl/update-layout ids {:layout-align-content value})))))]

    (if new-css-system
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:& title-bar {:collapsable? has-layout?
                       :collapsed?   (not open?)
                       :on-collapsed toggle-content
                       :title        "Layout"
                       :class        (stl/css-case :title-spacing-layout (not has-layout?))}
         (if (and (not multiple) (:layout values))
           [:div {:class (stl/css :title-actions)}
            (when ^boolean grid-enabled?
              [:div {:class (stl/css :layout-options)}
               [:& radio-buttons {:selected (d/name layout-type)
                                  :on-change toggle-layout-style
                                  :name "layout-style"
                                  :wide true}
                [:& radio-button {:value "flex"
                                  :id :flex}]
                [:& radio-button {:value "grid"
                                  :id :grid}]]])
            [:button {:class (stl/css :remove-layout)
                      :on-click on-remove-layout}
             i/remove-refactor]]
           [:div {:class (stl/css :title-actions)}
            [:button {:class (stl/css :add-layout)
                      :data-value :flex
                      :on-click on-set-layout}
             i/add-refactor]])]]

       (when (and open? has-layout?)
         (when (not= :multiple layout-type)
           (case layout-type
             :flex
             [:div  {:class (stl/css :flex-layout-menu)}
              [:div {:class (stl/css :first-row)}
               [:& align-row {:is-col? is-col?
                              :align-items align-items
                              :on-change set-align-items-refactor}]

               [:& direction-row-flex {:on-change set-direction-refactor
                                       :saved-dir saved-dir}]

               [:& wrap-row {:wrap-type wrap-type
                             :on-click toggle-wrap-refactor}]]

              [:div {:class (stl/css :second-row)}
               [:& justify-content-row {:is-col? is-col?
                                        :justify-content justify-content
                                         :on-change set-justify-content-refactor}]]
              (when (= :wrap wrap-type)
                [:div {:class (stl/css :third-row)}
                 [:& align-content-row {:is-col? is-col?
                                        :align-content align-content
                                        :on-change set-align-content-refactor}]])
              [:div {:class (stl/css :forth-row)}
               [:& gap-section {:is-col? is-col?
                                :wrap-type wrap-type
                                :gap-selected? gap-selected?
                                :on-change set-gap
                                :gap-value (:layout-gap values)}]

               [:& padding-section {:values values
                                    :on-change-style change-padding-type
                                    :on-change on-padding-change}]]]
             :grid ;; TODO Finish this with new UI
             [:div {:class (stl/css :grid-layout-menu)}
              [:div {:class (stl/css :first-row)}
                    [:div (stl/css :direction-edit)
                     [:div {:class (stl/css :direction)}
                      [:& direction-row-grid {:set-direction set-direction
                                              :on-click saved-dir}]
                      (when (= 1 (count ids))
                        [:div {:class (stl/css :edit)}
                         [:& grid-edit-mode {:id (first ids)}]])]]

                    [:div.layout-row
                     [:div.align-items-grid.row-title "Items"]
                     [:div.btn-wrapper.align-grid-items
                      [:& align-grid-row {:is-col? false
                                          :align-items align-items-row
                                          :set-align set-align-grid}]

                      [:& align-grid-row {:is-col? true
                                          :align-items align-items-column
                                          :set-align set-align-grid}]]]

                    [:div.layout-row
                     [:div.jusfiy-content-grid.row-title "Content"]
                     [:div.btn-wrapper.align-grid-content
                      [:& justify-grid-row {:is-col? true
                                            :justify-items grid-justify-content-column
                                            :set-justify set-justify-grid}]
                      [:& justify-grid-row {:is-col? false
                                            :justify-items grid-justify-content-row
                                            :set-justify set-justify-grid}]]]]]
             nil)))]

      [:div.element-set
       [:div.element-set-title
        [:*
         [:span "Layout"]

         (if ^boolean grid-enabled?
           [:div.title-actions
            [:div.layout-btns
             [:button {:on-click set-flex
                       :class (dom/classnames
                               :active (= :flex layout-type))} "Flex"]
             [:button {:on-click set-grid
                       :class (dom/classnames
                               :active (= :grid layout-type))} "Grid"]]

            (when (and (not multiple) (:layout values))
              [:button.remove-layout {:on-click on-remove-layout} i/minus])]

           [:div.title-actions
            (if (and (not multiple) (:layout values))
              [:button.remove-layout {:on-click on-remove-layout} i/minus]
              [:button.add-page {:data-value :flex
                                 :on-click on-set-layout} i/close])])]]

       (when (:layout values)
         (when (not= :multiple layout-type)
           (case layout-type
             :flex

             [:div.element-set-content.layout-menu
              [:div.layout-row
               [:div.direction-wrap.row-title "Direction"]
               [:div.btn-wrapper
                [:div.direction
                 [:& direction-row-flex {:on-change set-direction
                                         :saved-dir saved-dir}]]

                [:div.wrap-type
                 [:& wrap-row {:wrap-type wrap-type
                               :on-click set-wrap}]]]]

              (when (= :wrap wrap-type)
                [:div.layout-row
                 [:div.align-content.row-title "Content"]
                 [:div.btn-wrapper.align-content
                  [:& align-content-row {:is-col? is-col?
                                         :align-content align-content
                                         :on-change set-align-content}]]])

              [:div.layout-row
               [:div.align-items.row-title "Align"]
               [:div.btn-wrapper
                [:& align-row {:is-col? is-col?
                               :align-items align-items
                               :on-change set-align-items}]]]

              [:div.layout-row
               [:div.justify-content.row-title "Justify"]
               [:div.btn-wrapper.justify-content
                [:& justify-content-row {:is-col? is-col?
                                         :justify-content justify-content
                                         :on-change set-justify-content}]]]

              [:& gap-section {:is-col? is-col?
                               :wrap-type wrap-type
                               :gap-selected? gap-selected?
                               :on-change set-gap
                               :gap-value (:layout-gap values)}]

              [:& padding-section {:values values
                                   :on-change-style change-padding-type
                                   :on-change on-padding-change}]]

             :grid

             [:div.element-set-content.layout-menu
              [:div.layout-row
               [:div.direction-wrap.row-title "Direction"]
               [:div.btn-wrapper
                [:div.direction
                 [:*
                  (for [dir [:row :column]]
                    [:& direction-btn {:key (d/name dir)
                                       :dir dir
                                       :saved-dir saved-grid-dir
                                       :on-click set-direction
                                       :icon? true}])]]

                (when (= 1 (count ids))
                  [:div.edit-mode
                   [:& grid-edit-mode {:id (first ids)}]])]]

              [:div.layout-row
               [:div.align-items-grid.row-title "Items"]
               [:div.btn-wrapper.align-grid-items
                [:& align-grid-row {:is-col? false
                                    :align-items align-items-row
                                    :set-align set-align-grid}]

                [:& align-grid-row {:is-col? true
                                    :align-items align-items-column
                                    :set-align set-align-grid}]]]

              [:div.layout-row
               [:div.jusfiy-content-grid.row-title "Content"]
               [:div.btn-wrapper.align-grid-content
                [:& justify-grid-row {:is-col? true
                                      :justify-items grid-justify-content-column
                                      :set-justify set-justify-grid}]
                [:& justify-grid-row {:is-col? false
                                      :justify-items grid-justify-content-row
                                      :set-justify set-justify-grid}]]]]

                 ;; Default if not grid or flex
             nil)))])))

(mf/defc grid-layout-edition
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values"]))]}
  [{:keys [ids values] :as props}]
  (let [;; Gap
        gap-selected?  (mf/use-state :none)
        saved-grid-dir (:layout-grid-dir values)

        set-direction
        (fn [dir]
          (st/emit! (dwsl/update-layout ids {:layout-grid-dir dir})))

        set-gap
        (fn [gap-multiple? type val]
          (if gap-multiple?
            (st/emit! (dwsl/update-layout ids {:layout-gap {:row-gap val :column-gap val}}))
            (st/emit! (dwsl/update-layout ids {:layout-gap {type val}}))))

        ;; Padding
        change-padding-type
        (fn [type]
          (st/emit! (dwsl/update-layout ids {:layout-padding-type type})))

        on-padding-change
        (fn [type prop val]
          (cond
            (and (= type :simple) (= prop :p1))
            (st/emit! (dwsl/update-layout ids {:layout-padding {:p1 val :p3 val}}))

            (and (= type :simple) (= prop :p2))
            (st/emit! (dwsl/update-layout ids {:layout-padding {:p2 val :p4 val}}))

            :else
            (st/emit! (dwsl/update-layout ids {:layout-padding {prop val}}))))

        ;; Align grid
        align-items-row    (:layout-align-items values)
        align-items-column (:layout-justify-items values)

        set-items-grid
        (fn [value type]
          (if (= type :row)
            (st/emit! (dwsl/update-layout ids {:layout-align-items value}))
            (st/emit! (dwsl/update-layout ids {:layout-justify-items value}))))

        ;; Justify grid
        grid-justify-content-row    (:layout-align-content values)
        grid-justify-content-column (:layout-justify-content values)

        set-content-grid
        (mf/use-fn
         (mf/deps ids)
         (fn [value type]
           (if (= type :row)
             (st/emit! (dwsl/update-layout ids {:layout-justify-content value}))
             (st/emit! (dwsl/update-layout ids {:layout-align-content value})))))

        ;;Grid columns
        column-grid-values  (:layout-grid-columns values)
        grid-columns-open?  (mf/use-state false)
        toggle-columns-info (mf/use-fn
                             (fn [_]
                               (swap! grid-columns-open? not)))

        ;; Grid rows / columns
        rows-grid-values  (:layout-grid-rows values)
        grid-rows-open?  (mf/use-state false)
        toggle-rows-info
        (mf/use-fn
         (fn [_]
           (swap! grid-rows-open? not)))

        add-new-element
        (mf/use-fn
         (mf/deps ids)
         (fn [type value]
           (st/emit! (dwsl/add-layout-track ids type value))))

        remove-element
        (mf/use-fn
         (mf/deps ids)
         (fn [type index]
           (st/emit! (dwsl/remove-layout-track ids type index))))

        reorder-track
        (mf/use-fn
         (mf/deps ids)
         (fn [type from-index to-index]
           (st/emit! (dwsl/reorder-layout-track ids type from-index to-index))))

        hover-track
        (mf/use-fn
         (mf/deps ids)
         (fn [type index hover?]
           (st/emit! (dwsl/hover-layout-track ids type index hover?))))

        set-column-value
        (mf/use-fn
         (mf/deps ids)
         (fn [type index value]
           (st/emit! (dwsl/change-layout-track ids type index {:value value}))))

        set-column-type
        (mf/use-fn
         (mf/deps ids)
         (fn [type index track-type]
           (let [value (case track-type
                         :auto nil
                         :flex 1
                         :percent 20
                         :fixed 100)]
             (st/emit! (dwsl/change-layout-track ids type index {:value value
                                                                 :type track-type})))))]

    [:div.element-set
     [:div.element-set-title
      [:span "Grid Layout"]]

     [:div.element-set-content.layout-menu
      [:div.layout-row
       [:div.direction-wrap.row-title "Direction"]
       [:div.btn-wrapper
        [:div.direction
         (for [dir [:row :column]]
           [:& direction-btn {:key (d/name dir)
                              :dir dir
                              :saved-dir saved-grid-dir
                              :on-click set-direction
                              :icon? true}])]

        (when (= 1 (count ids))
          [:div.edit-mode
           [:& grid-edit-mode {:id (first ids)}]])]]

      [:div.layout-row
       [:div.align-items-grid.row-title "Items"]
       [:div.btn-wrapper.align-grid
        [:& align-grid-row {:is-col? false
                            :align-items align-items-row
                            :set-align set-items-grid}]

        [:& align-grid-row {:is-col? true
                            :align-items align-items-column
                            :set-align set-items-grid}]]]

      [:div.layout-row
       [:div.jusfiy-content-grid.row-title "Content"]
       [:div.btn-wrapper.align-grid-content
        [:& justify-grid-row {:is-col? true
                              :justify-items grid-justify-content-row
                              :set-justify set-content-grid}]
        [:& justify-grid-row {:is-col? false
                              :justify-items grid-justify-content-column
                              :set-justify set-content-grid}]]]
      [:& grid-columns-row {:is-col? true
                            :expanded? @grid-columns-open?
                            :toggle toggle-columns-info
                            :column-values column-grid-values
                            :add-new-element add-new-element
                            :set-column-value set-column-value
                            :set-column-type set-column-type
                            :remove-element remove-element
                            :reorder-track reorder-track
                            :hover-track hover-track}]

      [:& grid-columns-row {:is-col? false
                            :expanded? @grid-rows-open?
                            :toggle toggle-rows-info
                            :column-values rows-grid-values
                            :add-new-element add-new-element
                            :set-column-value set-column-value
                            :set-column-type set-column-type
                            :remove-element remove-element
                            :reorder-track reorder-track
                            :hover-track hover-track}]

      [:& gap-section {:gap-selected? gap-selected?
                       :on-change set-gap
                       :gap-value (:layout-gap values)}]

      [:& padding-section {:values values
                           :on-change-style change-padding-type
                           :on-change on-padding-change}]]]))
