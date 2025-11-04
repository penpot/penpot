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
   [app.config :as cf]
   [app.main.data.event :as-alias ev]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- dir-icons-refactor
  [val]
  (case val
    :row            deprecated-icon/grid-row
    :row-reverse    deprecated-icon/row-reverse
    :column         deprecated-icon/column
    :column-reverse deprecated-icon/column-reverse))

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
  [type val ^boolean column?]
  (case type
    :align-items
    (if column?
      (case val
        :start    deprecated-icon/align-items-column-start
        :end      deprecated-icon/align-items-column-end
        :center   deprecated-icon/align-items-column-center)
      (case val
        :start    deprecated-icon/align-items-row-start
        :end      deprecated-icon/align-items-row-end
        :center   deprecated-icon/align-items-row-center))

    :justify-content
    (if column?
      (case val
        :start         deprecated-icon/justify-content-column-start
        :end           deprecated-icon/justify-content-column-end
        :center        deprecated-icon/justify-content-column-center
        :space-around  deprecated-icon/justify-content-column-around
        :space-evenly  deprecated-icon/justify-content-column-evenly
        :space-between deprecated-icon/justify-content-column-between)
      (case val
        :start         deprecated-icon/justify-content-row-start
        :end           deprecated-icon/justify-content-row-end
        :center        deprecated-icon/justify-content-row-center
        :space-around  deprecated-icon/justify-content-row-around
        :space-evenly  deprecated-icon/justify-content-row-evenly
        :space-between deprecated-icon/justify-content-row-between))

    :align-content
    (if column?
      (case val
        :start         deprecated-icon/align-content-column-start
        :end           deprecated-icon/align-content-column-end
        :center        deprecated-icon/align-content-column-center
        :space-around  deprecated-icon/align-content-column-around
        :space-evenly  deprecated-icon/align-content-column-evenly
        :space-between deprecated-icon/align-content-column-between
        :stretch nil)

      (case val
        :start         deprecated-icon/align-content-row-start
        :end           deprecated-icon/align-content-row-end
        :center        deprecated-icon/align-content-row-center
        :space-around  deprecated-icon/align-content-row-around
        :space-evenly  deprecated-icon/align-content-row-evenly
        :space-between deprecated-icon/align-content-row-between
        :stretch nil))

    :align-self
    (if column?
      (case val
        :auto     deprecated-icon/remove-icon
        :start    deprecated-icon/align-self-row-left
        :end      deprecated-icon/align-self-row-right
        :center   deprecated-icon/align-self-row-center)
      (case val
        :auto     deprecated-icon/remove-icon
        :start    deprecated-icon/align-self-column-top
        :end      deprecated-icon/align-self-column-bottom
        :center   deprecated-icon/align-self-column-center))))

(defn get-layout-grid-icon
  [type val ^boolean column?]
  (case type
    :align-items
    (if column?
      (case val
        :auto     deprecated-icon/remove-icon
        :start    deprecated-icon/align-self-row-left
        :end      deprecated-icon/align-self-row-right
        :center   deprecated-icon/align-self-row-center)
      (case val
        :auto     deprecated-icon/remove-icon
        :start    deprecated-icon/align-self-column-top
        :end      deprecated-icon/align-self-column-bottom
        :center   deprecated-icon/align-self-column-center))

    :justify-items
    (if (not column?)
      (case val
        :start         deprecated-icon/align-content-column-start
        :center        deprecated-icon/align-content-column-center
        :end           deprecated-icon/align-content-column-end
        :space-around  deprecated-icon/align-content-column-around
        :space-between deprecated-icon/align-content-column-between
        :stretch       deprecated-icon/align-content-column-stretch)
      (case val
        :start         deprecated-icon/align-content-row-start
        :center        deprecated-icon/align-content-row-center
        :end           deprecated-icon/align-content-row-end
        :space-around  deprecated-icon/align-content-row-around
        :space-between deprecated-icon/align-content-row-between
        :stretch       deprecated-icon/align-content-row-stretch))))

(mf/defc direction-row-flex
  {::mf/props :obj
   ::mf/private true}
  [{:keys [value on-change]}]
  [:& radio-buttons {:class (stl/css :direction-row-flex)
                     :selected (d/name value)
                     :decode-fn keyword
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
                     :icon (dir-icons-refactor :column-reverse)}]])

(mf/defc wrap-row
  {::mf/props :obj}
  [{:keys [wrap-type on-click]}]
  [:button {:class (stl/css-case :wrap-button true
                                 :selected (= wrap-type :wrap))
            :title (if (= :wrap wrap-type)
                     "No wrap"
                     "Wrap")
            :on-click on-click}
   deprecated-icon/wrap])

(mf/defc align-row
  {::mf/props :obj}
  [{:keys [is-column value on-change]}]
  [:& radio-buttons {:class (stl/css :align-row)
                     :selected (d/name value)
                     :decode-fn keyword
                     :on-change on-change
                     :name "flex-align-items"}
   [:& radio-button {:value "start"
                     :icon  (get-layout-flex-icon :align-items :start is-column)
                     :title "Align items start"
                     :id     "align-items-start"}]
   [:& radio-button {:value "center"
                     :icon  (get-layout-flex-icon :align-items :center is-column)
                     :title "Align items center"
                     :id    "align-items-center"}]
   [:& radio-button {:value "end"
                     :icon  (get-layout-flex-icon :align-items :end is-column)
                     :title "Align items end"
                     :id    "align-items-end"}]])

(mf/defc align-content-row
  {::mf/props :obj}
  [{:keys [is-column value on-change]}]
  [:& radio-buttons {:class (stl/css :align-content-row)
                     :selected (d/name value)
                     :decode-fn keyword
                     :on-change on-change
                     :name "flex-align-content"}
   [:& radio-button {:value "start"
                     :icon  (get-layout-flex-icon :align-content :start is-column)
                     :title "Align content start"
                     :id    "align-content-start"}]
   [:& radio-button {:value "center"
                     :icon  (get-layout-flex-icon :align-content :center is-column)
                     :title "Align content center"
                     :id    "align-content-center"}]
   [:& radio-button {:value "end"
                     :icon  (get-layout-flex-icon :align-content :end is-column)
                     :title "Align content end"
                     :id    "align-content-end"}]
   [:& radio-button {:value "space-between"
                     :icon  (get-layout-flex-icon :align-content :space-between is-column)
                     :title "Align content space-between"
                     :id    "align-content-space-between"}]
   [:& radio-button {:value "space-around"
                     :icon  (get-layout-flex-icon :align-content :space-around is-column)
                     :title "Align content space-around"
                     :id    "align-content-space-around"}]
   [:& radio-button {:value "space-evenly"
                     :icon  (get-layout-flex-icon :align-content :space-evenly is-column)
                     :title "Align content space-evenly"
                     :id    "align-content-space-evenly"}]])

(mf/defc justify-content-row
  {::mf/props :obj}
  [{:keys [is-column justify-content on-change]}]
  [:& radio-buttons {:class (stl/css :justify-content-row)
                     :selected (d/name justify-content)
                     :on-change on-change
                     :name "flex-justify"}
   [:& radio-button {:value "start"
                     :icon  (get-layout-flex-icon :justify-content :start is-column)
                     :title "Justify content start"
                     :id    "justify-content-start"}]
   [:& radio-button {:value "center"
                     :icon  (get-layout-flex-icon :justify-content :center is-column)
                     :title "Justify content center"
                     :id    "justify-content-center"}]
   [:& radio-button {:value "end"
                     :icon  (get-layout-flex-icon :justify-content :end is-column)
                     :title "Justify content end"
                     :id    "justify-content-end"}]
   [:& radio-button {:value "space-between"
                     :icon  (get-layout-flex-icon :justify-content :space-between is-column)
                     :title "Justify content space-between"
                     :id    "justify-content-space-between"}]
   [:& radio-button {:value "space-around"
                     :icon  (get-layout-flex-icon :justify-content :space-around is-column)
                     :title "Justify content space-around"
                     :id    "justify-content-space-around"}]
   [:& radio-button {:value "space-evenly"
                     :icon  (get-layout-flex-icon :justify-content :space-evenly is-column)
                     :title "Justify content space-evenly"
                     :id    "justify-content-space-evenly"}]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PADDING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-padding
  ([p]
   (select-padding (= p :p1) (= p :p2) (= p :p3) (= p :p4)))
  ([p1? p2? p3? p4?]
   (st/emit! (udw/set-paddings-selected {:p1 p1? :p2 p2? :p3 p3? :p4 p4?}))))

(defn- on-padding-blur
  [_event]
  (select-padding false false false false))

(mf/defc simple-padding-selection
  {::mf/props :obj}
  [{:keys [value on-change]}]
  (let [p1 (:p1 value)
        p2 (:p2 value)
        p3 (:p3 value)
        p4 (:p4 value)

        p1 (if (and (not (= :multiple value))
                    (= p1 p3))
             p1
             nil)

        p2 (if (and (not (= :multiple value))
                    (= p2 p4))
             p2
             nil)

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [value event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "attr")
                          (keyword))]
             (on-change :simple attr value event))))

        on-focus
        (mf/use-fn
         (fn [event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "attr")
                          (keyword))]

             (case attr
               :p1 (select-padding true false true false)
               :p2 (select-padding false true false true))

             (dom/select-target event))))]

    [:div {:class (stl/css :paddings-simple)}
     [:div {:class (stl/css :padding-simple)
            :title (tr "workspace.layout_grid.editor.padding.vertical")}
      [:span {:class (stl/css :icon)}
       deprecated-icon/padding-top-bottom]
      [:> numeric-input*
       {:class (stl/css :numeric-input)
        :placeholder (tr "settings.multiple")
        :aria-label (tr "workspace.layout_grid.editor.padding.vertical")
        :data-attr "p1"
        :on-change on-change'
        :on-focus on-focus
        :nillable true
        :min 0
        :value p1}]]
     [:div {:class (stl/css :padding-simple)
            :title (tr "workspace.layout_grid.editor.padding.horizontal")}

      [:span {:class (stl/css :icon)}
       deprecated-icon/padding-left-right]
      [:> numeric-input*
       {:className (stl/css :numeric-input)
        :placeholder (tr "settings.multiple")
        :aria-label (tr "workspace.layout_grid.editor.padding.horizontal")
        :data-attr "p2"
        :on-change on-change'
        :on-focus on-focus
        :on-blur on-padding-blur
        :min 0
        :nillable true
        :value p2}]]]))

(mf/defc multiple-padding-selection
  {::mf/props :obj}
  [{:keys [value on-change]}]
  (let [p1 (:p1 value)
        p2 (:p2 value)
        p3 (:p3 value)
        p4 (:p4 value)

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [value event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "attr")
                          (keyword))]
             (on-change :multiple attr value event))))

        on-focus
        (mf/use-fn
         (fn [event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "attr")
                          (keyword))]

             (select-padding attr)
             (dom/select-target event))))]

    [:div {:class (stl/css :paddings-multiple)}
     [:div {:class (stl/css :padding-multiple)
            :title (tr "workspace.layout_grid.editor.padding.top")}
      [:span {:class (stl/css :icon)}
       deprecated-icon/padding-top]
      [:> numeric-input*
       {:class (stl/css :numeric-input)
        :placeholder "--"
        :aria-label (tr "workspace.layout_grid.editor.padding.top")
        :data-attr "p1"
        :on-change on-change'
        :on-focus on-focus
        :on-blur on-padding-blur
        :min 0
        :value p1}]]

     [:div {:class (stl/css :padding-multiple)
            :title (tr "workspace.layout_grid.editor.padding.right")}
      [:span {:class (stl/css :icon)}
       deprecated-icon/padding-right]
      [:> numeric-input*
       {:class (stl/css :numeric-input)
        :placeholder "--"
        :aria-label (tr "workspace.layout_grid.editor.padding.right")
        :data-attr "p2"
        :on-change on-change'
        :on-focus on-focus
        :on-blur on-padding-blur
        :min 0
        :value p2}]]

     [:div {:class (stl/css :padding-multiple)
            :title (tr "workspace.layout_grid.editor.padding.bottom")}
      [:span {:class (stl/css :icon)}
       deprecated-icon/padding-bottom]
      [:> numeric-input*
       {:class (stl/css :numeric-input)
        :placeholder "--"
        :aria-label (tr "workspace.layout_grid.editor.padding.bottom")
        :data-attr "p3"
        :on-change on-change'
        :on-focus on-focus
        :on-blur on-padding-blur
        :min 0
        :value p3}]]

     [:div {:class (stl/css :padding-multiple)
            :title (tr "workspace.layout_grid.editor.padding.left")}
      [:span {:class (stl/css :icon)}
       deprecated-icon/padding-left]
      [:> numeric-input*
       {:class (stl/css :numeric-input)
        :placeholder "--"
        :aria-label (tr "workspace.layout_grid.editor.padding.left")
        :data-attr "p4"
        :on-change on-change'
        :on-focus on-focus
        :on-blur on-padding-blur
        :min 0
        :value p4}]]]))

(mf/defc padding-section
  {::mf/props :obj}
  [{:keys [type on-type-change on-change] :as props}]
  (let [on-type-change'
        (mf/use-fn
         (mf/deps on-type-change)
         (fn [event]
           (let [type (-> (dom/get-current-target event)
                          (dom/get-data "type"))
                 type (if (= type "multiple") :simple :multiple)]
             (on-type-change type))))

        props (mf/spread-object props {:on-change on-change})]

    (mf/with-effect []
      ;; on destroy component
      (fn []
        (on-padding-blur nil)))

    [:div {:class (stl/css :padding-group)}
     [:div {:class (stl/css :padding-inputs)}
      (cond
        (= type :simple)
        [:> simple-padding-selection props]

        (= type :multiple)
        [:> multiple-padding-selection props])]

     [:button {:class (stl/css-case
                       :padding-toggle true
                       :selected (= type :multiple))
               :title (tr "workspace.layout_grid.editor.padding.expand")
               :aria-label (tr "workspace.layout_grid.editor.padding.expand")
               :data-type (d/name type)
               :on-click on-type-change'}
      deprecated-icon/padding-extended]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-gap!
  [value]
  (st/emit! (udw/set-gap-selected value)))

(defn- on-gap-focus
  [event]
  (let [type (-> (dom/get-current-target event)
                 (dom/get-data "type")
                 (keyword))]
    (select-gap! type)
    (dom/select-target event)))

(defn- on-gap-blur
  [_event]
  (select-gap! nil))

(mf/defc gap-section
  {::mf/props :obj}
  [{:keys [is-column wrap-type on-change value]
    :or {wrap-type :none}
    :as props}]
  (let [nowrap? (= :nowrap wrap-type)

        row-gap-disabled?
        (and ^boolean nowrap?
             (not ^boolean is-column))

        col-gap-disabled?
        (and ^boolean nowrap?
             ^boolean is-column)

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [value event]
           (let [target    (dom/get-current-target event)
                 wrap-type (dom/get-data target "wrap-type")
                 type      (keyword (dom/get-data target "type"))]
             (on-change (= "nowrap" wrap-type) type value event))))]

    (mf/with-effect []
      ;; on destroy component
      (fn []
        (on-gap-blur nil)))

    [:div {:class (stl/css :gap-group)}

     [:div {:class (stl/css-case
                    :row-gap true
                    :disabled row-gap-disabled?)
            :title "Row gap"}
      [:span {:class (stl/css :icon)} deprecated-icon/gap-vertical]
      [:> numeric-input*
       {:class (stl/css :numeric-input true)
        :no-validate true
        :placeholder "--"
        :data-type "row-gap"
        :data-wrap-type (d/name wrap-type)
        :on-focus on-gap-focus
        :on-change on-change'
        :on-blur on-gap-blur
        :nillable true
        :min 0
        :value (:row-gap value)
        :disabled row-gap-disabled?}]]

     [:div {:class (stl/css-case
                    :column-gap true
                    :disabled col-gap-disabled?)
            :title "Column gap"}
      [:span {:class (stl/css :icon)} deprecated-icon/gap-horizontal]
      [:> numeric-input*
       {:class (stl/css :numeric-input true)
        :no-validate true
        :placeholder "--"
        :data-type "column-gap"
        :data-wrap-type (d/name wrap-type)
        :on-focus on-gap-focus
        :on-change on-change'
        :on-blur on-gap-blur
        :nillable true
        :min 0
        :value (:column-gap value)
        :disabled col-gap-disabled?}]]]))

;; GRID COMPONENTS

(mf/defc direction-row-grid
  {::mf/props :obj}
  [{:keys [value on-change] :as props}]
  [:& radio-buttons {:class (stl/css :direction-row-grid)
                     :selected (d/name value)
                     :decode-fn keyword
                     :on-change on-change
                     :name "grid-direction"}
   [:& radio-button {:value "row"
                     :id "grid-direction-row"
                     :title "Row"
                     :icon (dir-icons-refactor :row)}]
   [:& radio-button {:value "column"
                     :id "grid-direction-column"
                     :title "Column"
                     :icon (dir-icons-refactor :column)}]])

(mf/defc grid-edit-mode
  {::mf/props :obj}
  [{:keys [id]}]
  (let [edition (mf/deref refs/selected-edition)
        active? (= id edition)

        toggle-edit-mode
        (mf/use-fn
         (mf/deps id edition)
         (fn []
           (if-not active?
             (st/emit! (udw/start-edition-mode id))
             (st/emit! :interrupt))))]
    [:button
     {:class (stl/css :edit-mode-btn)
      :alt  "Grid edit mode"
      :on-click toggle-edit-mode}
     (tr "workspace.layout_grid.editor.options.edit-grid")]))

(mf/defc align-grid-row
  {::mf/props :obj
   ::mf/private true}
  [{:keys [is-column value on-change]}]
  (let [type (if ^boolean is-column "column" "row")]
    [:& radio-buttons {:class (stl/css :align-grid-row)
                       :selected (d/name value)
                       :decode-fn keyword
                       :on-change on-change
                       :name (dm/str "flex-align-items-" type)}
     [:& radio-button {:value "start"
                       :icon  (get-layout-grid-icon :align-items :start is-column)
                       :title "Align items start"
                       :id     (dm/str "align-items-start-" type)}]
     [:& radio-button {:value "center"
                       :icon  (get-layout-grid-icon :align-items :center is-column)
                       :title "Align items center"
                       :id    (dm/str "align-items-center-" type)}]
     [:& radio-button {:value "end"
                       :icon  (get-layout-grid-icon :align-items :end is-column)
                       :title "Align items end"
                       :id    (dm/str "align-items-end-" type)}]]))

(mf/defc justify-grid-row
  {::mf/props :obj
   ::mf/private :obj}
  [{:keys [is-column value on-change]}]
  (let [type (if ^boolean is-column "column" "row")]
    [:& radio-buttons {:class (stl/css :justify-grid-row)
                       :selected (d/name value)
                       :on-change on-change
                       :decode-fn keyword
                       :name (dm/str "grid-justify-items-" type)}

     [:& radio-button {:key "justify-item-start"
                       :value "start"
                       :icon (get-layout-grid-icon :justify-items :start is-column)
                       :title "Justify items start"
                       :id (dm/str "justify-items-start-" type)}]

     [:& radio-button {:key "justify-item-center"
                       :value "center"
                       :icon (get-layout-grid-icon :justify-items :center is-column)
                       :title "Justify items center"
                       :id (dm/str "justify-items-center-" type)}]

     [:& radio-button {:key "justify-item-end"
                       :value "end"
                       :icon (get-layout-grid-icon :justify-items :end is-column)
                       :title "Justify items end"
                       :id (dm/str "justify-items-end-" type)}]

     [:& radio-button {:key "justify-item-space-around"
                       :value "space-around"
                       :icon (get-layout-grid-icon :justify-items :space-around is-column)
                       :title "Justify items space-around"
                       :id (dm/str "justify-items-space-around-" type)}]

     [:& radio-button {:key "justify-item-space-between"
                       :value "space-between"
                       :icon (get-layout-grid-icon :justify-items :space-between is-column)
                       :title "Justify items space-between"
                       :id (dm/str "justify-items-space-between-" type)}]

     [:& radio-button {:key "justify-item-stretch"
                       :value "stretch"
                       :icon (get-layout-grid-icon :justify-items :stretch is-column)
                       :title "Justify items stretch"
                       :id (dm/str "justify-items-stretch-" type)}]]))

(defn- manage-values
  [{:keys [type value]}]
  (case type
    :auto "auto"
    :percent (fmt/format-percent (/ value 100))
    :flex    (fmt/format-frs value)
    :fixed   (fmt/format-pixels value)
    value))

(mf/defc grid-track-info
  {::mf/props :obj}
  [{:keys [is-column
           type
           index
           column
           set-column-value
           set-column-type
           remove-element
           reorder-track
           hover-track
           on-select-track]}]

  (let [drop-track
        (mf/use-fn
         (mf/deps type reorder-track index)
         (fn [drop-position data event]
           (reorder-track type (:index data) (if (= :top drop-position) (dec index) index) (not (kbd/mod? event)))))

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

        handle-select-track
        (mf/use-fn
         (mf/deps on-select-track type index)
         (fn []
           (when on-select-track
             (on-select-track type index))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/grid-track"
         :on-drop drop-track
         :data {:is-column is-column
                :index index
                :column column}
         :draggable? true)]

    [:div {:class (stl/css-case :track-info true
                                :dnd-over-top (or (= (:over dprops) :top)
                                                  (= (:over dprops) :center))
                                :dnd-over-bot (= (:over dprops) :bot))
           :ref dref
           :on-pointer-enter pointer-enter
           :on-pointer-leave pointer-leave}

     [:div {:class (stl/css :track-info-container)}
      [:div {:class (stl/css :track-info-dir-icon)
             :on-click handle-select-track}
       (if is-column deprecated-icon/flex-vertical deprecated-icon/flex-horizontal)]

      [:div {:class (stl/css :track-info-value)}
       [:> numeric-input* {:no-validate true
                           :value (:value column)
                           :on-change #(set-column-value type index %)
                           :placeholder "--"
                           :min 0
                           :disabled (= :auto (:type column))}]]

      [:div {:class (stl/css :track-info-unit)}
       [:& select {:class (stl/css :track-info-unit-selector)
                   :default-value (:type column)
                   :options [{:value :flex :label "FR"}
                             {:value :auto :label "AUTO"}
                             {:value :fixed :label "PX"}
                             {:value :percent :label "%"}]
                   :on-change #(set-column-type type index %)}]]]

     [:> icon-button* {:variant "ghost"
                       :aria-label (tr "workspace.shape.menu.delete")
                       :on-click remove-element
                       :data-type type
                       :data-index index
                       :icon i/remove}]]))

(mf/defc grid-columns-row
  {::mf/props :obj}
  [{:keys [is-column expanded? column-values toggle add-new-element set-column-value set-column-type
           remove-element reorder-track hover-track on-select-track]}]
  (let [column-num (count column-values)
        direction (if (> column-num 1)
                    (if ^boolean is-column "Columns " "Rows ")
                    (if ^boolean is-column "Column " "Row "))

        track-name (dm/str direction  (if (= column-num 0) " - empty" column-num))
        track-detail (str/join ", " (map manage-values column-values))

        type (if is-column :column :row)
        testid (when (not is-column) "inspect-layout-rows")

        add-track
        #(do
           (when-not expanded? (toggle))
           (add-new-element type ctl/default-track-value))]

    [:div {:class (stl/css :grid-tracks) :data-testid testid}
     [:div {:class (stl/css :grid-track-header)}
      [:button {:class (stl/css :expand-icon) :on-click toggle} deprecated-icon/menu]
      [:div {:class (stl/css :track-title) :on-click toggle}
       [:div {:class (stl/css :track-name) :title track-name} track-name]
       [:div {:class (stl/css :track-detail) :title track-detail} track-detail]]
      [:button {:class (stl/css :add-column) :on-click add-track} deprecated-icon/add]]

     (when expanded?
       [:> h/sortable-container* {}
        [:div {:class (stl/css :grid-tracks-info-container)}
         (for [[index column] (d/enumerate column-values)]
           [:& grid-track-info {:key (dm/str index "-" (d/name type))
                                :type type
                                :is-column is-column
                                :index index
                                :column column
                                :set-column-value set-column-value
                                :set-column-type set-column-type
                                :remove-element remove-element
                                :reorder-track reorder-track
                                :hover-track hover-track
                                :on-select-track on-select-track}])]])]))

;; LAYOUT COMPONENT

(defn- open-flex-help
  [_]
  (st/emit! (dom/open-new-window cf/flex-help-uri)))

(defn- open-grid-help
  [_]
  (st/emit! (dom/open-new-window cf/grid-help-uri)))

(mf/defc layout-container-menu
  {::mf/memo #{:ids :values :multiple}
   ::mf/props :obj}
  [{:keys [ids values multiple]}]
  (let [;; Display
        layout-type    (:layout values)
        has-layout?    (some? layout-type)

        show-dropdown* (mf/use-state false)
        show-dropdown? @show-dropdown*

        open*          (mf/use-state #(if layout-type true false))
        open?          (deref open*)

        on-toggle-visibility
        (mf/use-fn #(swap! open* not))

        on-add-layout
        (mf/use-fn
         (fn [event]
           (let [type (-> (dom/get-current-target event)
                          (dom/get-data "type")
                          (keyword))]
             (st/emit! (with-meta (dwsl/create-layout type)
                         {::ev/origin "workspace:sidebar"}))

             (reset! open* true))))

        on-remove-layout
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (dwsl/remove-layout ids))
           (reset! open* false)))

        saved-dir (:layout-flex-dir values)
        is-column (or (= :column saved-dir) (= :column-reverse saved-dir))

        ;; Wrap type
        wrap-type (:layout-wrap-type values)

        toggle-wrap
        (mf/use-fn
         (mf/deps wrap-type ids)
         (fn []
           (let [type (if (= wrap-type :wrap)
                        :nowrap
                        :wrap)]
             (st/emit! (dwsl/update-layout ids {:layout-wrap-type type})))))


        ;; Align items
        align-items         (:layout-align-items values)

        set-align-items
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-align-items (keyword value)}))))

        ;; Justify content
        justify-content     (:layout-justify-content values)

        set-justify-content
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-justify-content (keyword value)}))))

        ;; Align content
        align-content         (:layout-align-content values)

        ;; FIXME revisit???
        on-align-content-change
        (mf/use-fn
         (mf/deps ids align-content)
         (fn [value]
           (if (= align-content value)
             (st/emit! (dwsl/update-layout ids {:layout-align-content :stretch}))
             (st/emit! (dwsl/update-layout ids {:layout-align-content (keyword value)})))))

        ;; Gap
        on-gap-change
        (fn [multiple? type val]
          (let [val (mth/finite val 0)]
            (cond
              ^boolean multiple?
              (st/emit! (dwsl/update-layout ids {:layout-gap {:row-gap val :column-gap val}}))

              (some? type)
              (st/emit! (dwsl/update-layout ids {:layout-gap {type val}})))))

        ;; Padding
        on-padding-type-change
        (mf/use-fn
         (mf/deps ids)
         (fn [type]
           (st/emit! (dwsl/update-layout ids {:layout-padding-type type}))))

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

               (some? prop)
               (st/emit! (dwsl/update-layout ids {:layout-padding {prop val}}))))))

        ;; Grid-direction

        saved-grid-dir (:layout-grid-dir values)

        on-direction-change
        (mf/use-fn
         (mf/deps layout-type ids)
         (fn [dir]
           (if (= :flex layout-type)
             (st/emit! (dwsl/update-layout ids {:layout-flex-dir dir}))
             (st/emit! (dwsl/update-layout ids {:layout-grid-dir dir})))))

        ;; Align grid
        align-items-row    (:layout-align-items values)
        align-items-column (:layout-justify-items values)

        on-column-align-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-justify-items value}))))

        on-row-align-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-align-items value}))))

        ;; Justify grid
        grid-justify-content-row    (:layout-justify-content values)
        grid-justify-content-column (:layout-align-content values)

        on-column-justify-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-align-content value}))))

        on-row-justify-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-justify-content value}))))

        on-toggle-dropdown-visibility
        (mf/use-fn #(swap! show-dropdown* not))

        on-hide-dropdown
        (mf/use-fn #(reset! show-dropdown* false))]

    [:div {:class (stl/css :element-set) :data-testid "inspect-layout"}
     [:div {:class (stl/css :element-title)}
      [:> title-bar*
       {:collapsable has-layout?
        :collapsed (not open?)
        :on-collapsed on-toggle-visibility
        :title "Layout"
        :class (stl/css-case :title-spacing-layout (not has-layout?))}

       (if (and (not multiple) (:layout values))
         [:div {:class (stl/css :title-actions)}
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.shape.menu.add-layout")
                            :on-click on-toggle-dropdown-visibility
                            :icon i/menu}]

          [:& dropdown {:show show-dropdown?
                        :on-close on-hide-dropdown}
           [:div {:class (stl/css :layout-options)}
            [:button {:class (stl/css :layout-option)
                      :data-type "flex"
                      :on-click on-add-layout}
             "Flex layout"]
            [:button {:class (stl/css :layout-option)
                      :data-type "grid"
                      :on-click on-add-layout}
             "Grid layout"]]]

          (when has-layout?
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "workspace.shape.menu.remove-layout")
                              :on-click on-remove-layout
                              :icon i/remove}])]

         [:div {:class (stl/css :title-actions)}
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.shape.menu.add-layout")
                            :on-click on-toggle-dropdown-visibility
                            :icon i/add}]

          [:& dropdown {:show show-dropdown?
                        :on-close on-hide-dropdown}
           [:div {:class (stl/css :layout-options)}
            [:button {:class (stl/css :layout-option)
                      :data-type "flex"
                      :on-click on-add-layout}
             "Flex layout"]
            [:button {:class (stl/css :layout-option)
                      :data-type "grid"
                      :on-click on-add-layout}
             "Grid layout"]]]

          (when has-layout?
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "workspace.shape.menu.delete")
                              :on-click on-remove-layout
                              :icon i/remove}])])]]

     (when (and ^boolean open?
                ^boolean has-layout?
                (not= :multiple layout-type))
       (case layout-type
         :flex
         [:div  {:class (stl/css :flex-layout-menu)}
          [:div {:class (stl/css :first-row)}
           [:& align-row {:is-column is-column
                          :value align-items
                          :on-change set-align-items}]

           [:& direction-row-flex {:on-change on-direction-change
                                   :value saved-dir}]

           [:& wrap-row {:wrap-type wrap-type
                         :on-click toggle-wrap}]]

          [:div {:class (stl/css :second-row :help-button-wrapper)}
           [:& justify-content-row {:is-column is-column
                                    :justify-content justify-content
                                    :on-change set-justify-content}]

           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.help-center")
                             :on-click open-flex-help
                             :icon i/help}]]
          (when (= :wrap wrap-type)
            [:div {:class (stl/css :third-row)}
             [:& align-content-row {:is-column is-column
                                    :value align-content
                                    :on-change on-align-content-change}]])
          [:div {:class (stl/css :forth-row)}
           [:& gap-section {:is-column is-column
                            :wrap-type wrap-type
                            :on-change on-gap-change
                            :value (:layout-gap values)}]

           [:& padding-section {:value (:layout-padding values)
                                :type (:layout-padding-type values)
                                :on-type-change on-padding-type-change
                                :on-change on-padding-change}]]]

         :grid
         [:div {:class (stl/css :grid-layout-menu)}
          (when (= 1 (count ids))
            [:div {:class (stl/css :edit-grid-wrapper)}
             [:& grid-edit-mode {:id (first ids)}]
             [:> icon-button* {:variant "ghost"
                               :aria-label (tr "labels.help-center")
                               :on-click open-grid-help
                               :icon i/help}]])

          [:div {:class (stl/css :first-row)}
           [:div {:class (stl/css :direction-edit)}
            [:div {:class (stl/css :direction)}
             [:& direction-row-grid {:value saved-grid-dir
                                     :on-change on-direction-change}]]]

           [:& align-grid-row {:is-column false
                               :value align-items-row
                               :on-change on-row-align-change}]
           [:& align-grid-row {:is-column true
                               :value align-items-column
                               :on-change on-column-align-change}]]

          [:div {:class (stl/css :row :grid-layout-align)}
           [:& justify-grid-row {:is-column true
                                 :value grid-justify-content-column
                                 :on-change on-column-justify-change}]
           [:& justify-grid-row {:is-column false
                                 :value grid-justify-content-row
                                 :on-change on-row-justify-change}]]

          [:div {:class (stl/css :gap-row)}
           [:& gap-section {:on-change on-gap-change
                            :value (:layout-gap values)}]]
          [:div {:class (stl/css :padding-row)}
           [:& padding-section {:value (:layout-padding values)
                                :type (:layout-padding-type values)
                                :on-type-change on-padding-type-change
                                :on-change on-padding-change}]]]

         nil))]))

(mf/defc grid-layout-edition
  {::mf/memo #{:ids :values}
   ::mf/props :obj}
  [{:keys [ids values]}]
  (let [;; Gap
        saved-grid-dir (:layout-grid-dir values)

        on-direction-change
        (mf/use-fn
         (mf/deps ids)
         (fn [dir]
           (st/emit! (dwsl/update-layout ids {:layout-grid-dir dir}))))

        on-gap-change
        (mf/use-fn
         (mf/deps ids)
         (fn [multiple? type val]
           (let [val (mth/finite val 0)]
             (if multiple?
               (st/emit! (dwsl/update-layout ids {:layout-gap {:row-gap val :column-gap val}}))
               (st/emit! (dwsl/update-layout ids {:layout-gap {type val}}))))))

        ;; Padding
        on-padding-type-change
        (mf/use-fn
         (mf/deps ids)
         (fn [type]
           (st/emit! (dwsl/update-layout ids {:layout-padding-type type}))))

        on-padding-change
        (fn [type prop val]
          (let [val (mth/finite val 0)]
            (cond
              (and (= type :simple) (= prop :p1))
              (st/emit! (dwsl/update-layout ids {:layout-padding {:p1 val :p3 val}}))

              (and (= type :simple) (= prop :p2))
              (st/emit! (dwsl/update-layout ids {:layout-padding {:p2 val :p4 val}}))

              :else
              (st/emit! (dwsl/update-layout ids {:layout-padding {prop val}})))))

        ;; Align grid
        align-items-row    (:layout-align-items values)
        align-items-column (:layout-justify-items values)

        on-column-align-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-justify-items value}))))

        on-row-align-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-align-items value}))))

        ;; Justify grid
        grid-justify-content-row    (:layout-justify-content values)
        grid-justify-content-column (:layout-align-content values)

        on-column-justify-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-align-content value}))))

        on-row-justify-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout ids {:layout-justify-content value}))))

        columns-open?    (mf/use-state false)
        rows-open?       (mf/use-state false)

        column-values    (:layout-grid-columns values)
        rows-values      (:layout-grid-rows values)

        toggle-columns-open
        (mf/use-fn #(swap! columns-open? not))

        toggle-rows-open
        (mf/use-fn #(swap! rows-open? not))

        add-new-element
        (mf/use-fn
         (mf/deps ids)
         (fn [type value]
           (st/emit! (dwsl/add-layout-track ids type value))))

        remove-element
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [type (-> (dom/get-current-target event)
                          (dom/get-data "type")
                          (d/read-string))
                 index (-> (dom/get-current-target event)
                           (dom/get-data "index")
                           (d/parse-integer))]
             (st/emit! (dwsl/remove-layout-track ids type index)))))

        reorder-track
        (mf/use-fn
         (mf/deps ids)
         (fn [type from-index to-index move-content?]
           (st/emit! (dwsl/reorder-layout-track ids type from-index to-index move-content?))))

        hover-track
        (mf/use-fn
         (mf/deps ids)
         (fn [type index hover?]
           (st/emit! (dwsl/hover-layout-track ids type index hover?))))

        handle-select-track
        (mf/use-fn
         (mf/deps ids)
         (fn [type index]
           (st/emit! (dwge/select-track-cells (first ids) type index))))

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
                                                                 :type track-type})))))
        handle-locate-grid
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (dwge/locate-board (first ids)))))]

    [:div {:class (stl/css :grid-layout-menu)}
     [:div {:class (stl/css :grid-first-row)}
      [:div {:class (stl/css :grid-layout-menu-title)} "GRID LAYOUT"]
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :help-button)
                        :aria-label (tr "labels.help-center")
                        :on-click open-grid-help
                        :icon i/help}]
      [:button {:class (stl/css :exit-btn)
                :on-click #(st/emit! (udw/clear-edition-mode))}
       (tr "workspace.layout_grid.editor.options.exit")]]

     [:div {:class (stl/css :row :first-row)}
      [:div {:class (stl/css :direction-edit)}
       [:div {:class (stl/css :direction)}
        [:& direction-row-grid {:value saved-grid-dir
                                :on-change on-direction-change}]]]

      [:& align-grid-row {:is-column false
                          :value align-items-row
                          :on-change on-row-align-change}]

      [:& align-grid-row {:is-column true
                          :value align-items-column
                          :on-change on-column-align-change}]]

     [:div {:class (stl/css :row :grid-layout-align)}
      [:& justify-grid-row {:is-column true
                            :value grid-justify-content-column
                            :on-change on-column-justify-change}]
      [:& justify-grid-row {:is-column false
                            :value grid-justify-content-row
                            :on-change on-row-justify-change}]

      [:> icon-button* {:variant "ghost"
                        :class (stl/css :locate-button)
                        :aria-label (tr "workspace.layout_grid.editor.top-bar.locate.tooltip")
                        :on-click handle-locate-grid
                        :icon i/locate}]]

     [:div {:class (stl/css :gap-row)}
      [:& gap-section {:on-change on-gap-change
                       :value (:layout-gap values)}]]

     [:div {:class (stl/css :padding-row :padding-section)}
      [:& padding-section {:value (:layout-padding values)
                           :type (:layout-padding-type values)
                           :on-type-change on-padding-type-change
                           :on-change on-padding-change}]]

     [:div {:class (stl/css :grid-tracks-row)}
      [:& grid-columns-row {:is-column true
                            :expanded? @columns-open?
                            :toggle toggle-columns-open
                            :column-values column-values
                            :add-new-element add-new-element
                            :set-column-value set-column-value
                            :set-column-type set-column-type
                            :remove-element remove-element
                            :reorder-track reorder-track
                            :hover-track hover-track
                            :on-select-track handle-select-track}]

      [:& grid-columns-row {:is-column false
                            :expanded? @rows-open?
                            :toggle toggle-rows-open
                            :column-values rows-values
                            :add-new-element add-new-element
                            :set-column-value set-column-value
                            :set-column-type set-column-type
                            :remove-element remove-element
                            :reorder-track reorder-track
                            :hover-track hover-track
                            :on-select-track handle-select-track}]]]))
