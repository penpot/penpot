;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-container
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

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

(defn get-layout-grid-icon
  [type val is-col?]
  (case type
    :justify-items
    (if is-col?
      (case val
        :start         i/grid-justify-content-column-start
        :end           i/grid-justify-content-column-end
        :center        i/grid-justify-content-column-center
        :space-around  i/grid-justify-content-column-around
        :space-between i/grid-justify-content-column-between)
      (case val
        :start         i/grid-justify-content-row-start
        :end           i/grid-justify-content-row-end
        :center        i/grid-justify-content-row-center
        :space-around  i/grid-justify-content-row-around
        :space-between i/grid-justify-content-row-between))))

(mf/defc direction-btn
  [{:keys [dir saved-dir set-direction icon?] :as props}]
  (let [handle-on-click
        (mf/use-callback
         (mf/deps set-direction dir)
         (fn []
           (when (some? set-direction)
             (set-direction dir))))]

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

(mf/defc wrap-row
  [{:keys [wrap-type set-wrap] :as props}]
  [:*
   [:button.tooltip.tooltip-bottom
    {:class  (dom/classnames :active  (= wrap-type :nowrap))
     :alt    "No wrap"
     :on-click #(set-wrap :nowrap)
     :style {:padding 0}}
    [:span.no-wrap i/minus]]
   [:button.wrap.tooltip.tooltip-bottom
    {:class  (dom/classnames :active  (= wrap-type :wrap))
     :alt    "Wrap"
     :on-click #(set-wrap :wrap)}
    i/auto-wrap]])

(mf/defc align-row
  [{:keys [is-col? align-items set-align] :as props}]

  [:div.align-items-style
   (for [align [:start :center :end #_:stretch #_:baseline]]
     [:button.align-start.tooltip
      {:class    (dom/classnames :active  (= align-items align)
                                 :tooltip-bottom-left (not= align :start)
                                 :tooltip-bottom (= align :start))
       :alt      (dm/str "Align items " (d/name align))
       :on-click #(set-align align)
       :key      (dm/str "align-items" (d/name align))}
      (get-layout-flex-icon :align-items align is-col?)])])

(mf/defc align-content-row
  [{:keys [is-col? align-content set-align-content] :as props}]
  [:*
   [:div.align-content-style
    (for [align [:start :center :end]]
      [:button.align-content.tooltip
       {:class    (dom/classnames :active  (= align-content align)
                                  :tooltip-bottom-left (not= align :start)
                                  :tooltip-bottom (= align :start))
        :alt      (dm/str "Align content " (d/name align))
        :on-click #(set-align-content align)
        :key      (dm/str  "align-content" (d/name align))}
       (get-layout-flex-icon :align-content align is-col?)])]
   [:div.align-content-style
    (for [align [:space-between :space-around :space-evenly]]
      [:button.align-content.tooltip
       {:class    (dom/classnames :active  (= align-content align)
                                  :tooltip-bottom-left (not= align :space-between)
                                  :tooltip-bottom (= align :space-between))
        :alt      (dm/str "Align content " (d/name align))
        :on-click #(set-align-content align)
        :key      (dm/str  "align-content" (d/name align))}
       (get-layout-flex-icon :align-content align is-col?)])]])

(mf/defc justify-content-row
  [{:keys [is-col? justify-content set-justify] :as props}]
  [:*
   [:div.justify-content-style
    (for [justify [:start :center :end]]
      [:button.justify.tooltip
       {:class    (dom/classnames :active  (= justify-content justify)
                                  :tooltip-bottom-left (not= justify :start)
                                  :tooltip-bottom (= justify :start))
        :alt      (dm/str "Justify content " (d/name justify))
        :on-click #(set-justify justify)
        :key (dm/str "justify-content" (d/name justify))}
       (get-layout-flex-icon :justify-content justify is-col?)])]
   [:div.justify-content-style
    (for [justify [:space-between :space-around :space-evenly]]
      [:button.justify.tooltip
       {:class    (dom/classnames :active  (= justify-content justify)
                                  :tooltip-bottom-left (not= justify :space-between)
                                  :tooltip-bottom (= justify :space-between))
        :alt      (dm/str "Justify content " (d/name justify))
        :on-click #(set-justify justify)
        :key (dm/str "justify-content" (d/name justify))}
       (get-layout-flex-icon :justify-content justify is-col?)])]])

(mf/defc padding-section
  [{:keys [values on-change-style on-change] :as props}]

  (let [padding-type (:layout-padding-type values)
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

    [:div.padding-row
     (cond
       (= padding-type :simple)

       [:div.padding-group
        [:div.padding-item.tooltip.tooltip-bottom-left
         {:alt "Vertical padding"}
         [:span.icon.rotated i/auto-padding-both-sides]
         [:> numeric-input
          {:placeholder "--"
           :on-change (partial on-change :simple :p1)
           :on-focus #(select-paddings true false true false)
           :on-blur #(do
                       (dom/select-target %)
                       (select-paddings false false false false))
           :value p1}]]

        [:div.padding-item.tooltip.tooltip-bottom-left
         {:alt "Horizontal padding"}
         [:span.icon i/auto-padding-both-sides]
         [:> numeric-input
          {:placeholder "--"
           :on-change (partial on-change :simple :p2)
           :on-focus #(do (dom/select-target %)
                          (select-paddings false true false true))
           :on-blur #(select-paddings false false false false)
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
            [:> numeric-input
             {:placeholder "--"
              :on-change (partial on-change :multiple num)
              :on-focus #(do (dom/select-target %)
                             (select-padding num))
              :on-blur #(select-paddings false false false false)
              :value (num (:layout-padding values))}]]])])

     [:div.padding-icons
      [:div.padding-icon.tooltip.tooltip-bottom-left
       {:class (dom/classnames :selected (= padding-type :multiple))
        :alt "Independent paddings"
        :on-click #(on-change-style (if (= padding-type :multiple) :simple :multiple))}
       i/auto-padding-side]]]))

(mf/defc gap-section
  [{:keys [is-col? wrap-type gap-selected? set-gap gap-value]}]
  (let [select-gap
        (fn [gap]
          (st/emit! (udw/set-gap-selected gap)))]

    (mf/use-effect
     (fn []
       (fn []
         ;;on destroy component
         (select-gap nil))))

    [:div.layout-row
     [:div.gap.row-title "Gap"]
     [:div.gap-group
      [:div.gap-row.tooltip.tooltip-bottom-left
       {:alt "Column gap"}
       [:span.icon
        i/auto-gap]
       [:> numeric-input {:no-validate true
                          :placeholder "--"
                        :on-focus (fn [event]
                                      (select-gap :column-gap)
                                      (reset! gap-selected? :column-gap)
                                      (dom/select-target event))
                          :on-change (partial set-gap (= :nowrap wrap-type) :column-gap)
                          :on-blur (fn [_]
                                     (select-gap nil)
                                     (reset! gap-selected? :none))
                          :value (:column-gap gap-value)
                          :disabled (and (= :nowrap wrap-type) is-col?)}]]

      [:div.gap-row.tooltip.tooltip-bottom-left
       {:alt "Row gap"}
       [:span.icon.rotated
        i/auto-gap]
       [:> numeric-input {:no-validate true
                          :placeholder "--"
                        :on-focus (fn [event]
                                      (select-gap :row-gap)
                                      (reset! gap-selected? :row-gap)
                                      (dom/select-target event))
                          :on-change (partial set-gap (= :nowrap wrap-type) :row-gap)
                          :on-blur (fn [_]
                                     (select-gap nil)
                                     (reset! gap-selected? :none))
                          :value (:row-gap gap-value)
                          :disabled (and (= :nowrap wrap-type) (not is-col?))}]]]]))

(mf/defc grid-edit-mode
  [{:keys [id] :as props}]
  (let [edition (mf/deref refs/selected-edition)
        active? (= id edition)

        toggle-edit-mode
        (mf/use-callback
         (mf/deps id edition)
         (fn []
           (if-not active?
             (st/emit! (udw/start-edition-mode id))
             (st/emit! :interrupt))))]

    [:button.tooltip.tooltip-bottom-left
     {:class  (dom/classnames :active  active?)
      :alt    "Grid edit mode"
      :on-click #(toggle-edit-mode)
      :style {:padding 0}}
     i/grid-layout-mode]))

(mf/defc align-grid-row
  [{:keys [is-col? align-items set-align] :as props}]
  (let [type (if is-col? :column :row)]
    [:div.align-items-style
     (for [align [:start :center :end :stretch :baseline]]
       [:button.align-start.tooltip
        {:class    (dom/classnames :active  (= align-items align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (dm/str "Align items " (d/name align))
         :on-click #(set-align align type)
         :key      (dm/str "align-items" (d/name align))}
        (get-layout-flex-icon :align-items align is-col?)])]))

(mf/defc justify-grid-row
  [{:keys [is-col? justify-items set-justify] :as props}]
  (let [type (if is-col? :column :row)]
    [:div.justify-content-style
     (for [align [:start :center :end :space-around :space-between]]
       [:button.align-start.tooltip
        {:class    (dom/classnames :active  (= justify-items align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (dm/str "Justify content " (d/name align))
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

(mf/defc grid-columns-row
  [{:keys [is-col? expanded? column-values toggle add-new-element set-column-value set-column-type remove-element] :as props}]
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
                                        (add-new-element type {:type :fixed :value 100}))} i/plus]]

     (when expanded?
       [:div.columns-info-wrapper
        (for [[index column] (d/enumerate column-values)]
          [:div.column-info
           [:div.direction-grid-icon
            (if is-col?
              i/layout-rows
              i/layout-columns)]

           [:div.grid-column-value
            [:> numeric-input {:no-validate true
                               :value (:value column)
                               :on-change #(set-column-value type index %)
                               :placeholder "--"}]]
           [:div.grid-column-unit
            [:& select
             {:class "grid-column-unit-selector"
              :default-value (:type column)
              :options [{:value :flex :label "fr"}
                        {:value :auto :label "auto"}
                        {:value :fixed :label "px"}
                        {:value :percent :label "%"}]
              :on-change #(set-column-type type index %)}]]
           [:button.remove-grid-column
            {:on-click #(remove-element type index)}
            i/minus]])])]))

(mf/defc layout-container-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "multiple"]))]}
  [{:keys [ids _type values  multiple] :as props}]
  (let [open?               (mf/use-state false)

        ;; Display
        layout-type         (:layout values)

        on-add-layout
        (fn [type]
          (st/emit! (dwsl/create-layout type))
          (reset! open? true))


        on-remove-layout
        (fn [_]
          (st/emit! (dwsl/remove-layout ids))
          (reset! open? false))

        _set-flex
        (fn []
          (st/emit! (dwsl/remove-layout ids))
          (on-add-layout :flex))

        _set-grid
        (fn []
          (st/emit! (dwsl/remove-layout ids))
          (on-add-layout :grid))

        ;; Flex-direction

        saved-dir (:layout-flex-dir values)
        is-col? (or (= :column saved-dir) (= :column-reverse saved-dir))

        ;; Wrap type

        wrap-type          (:layout-wrap-type values)
        set-wrap            (fn [type]
                              (st/emit! (dwsl/update-layout ids {:layout-wrap-type type})))
        ;; Align items

        align-items         (:layout-align-items values)
        set-align-items     (fn [value]
                              (st/emit! (dwsl/update-layout ids {:layout-align-items value})))

        ;; Justify content

        justify-content     (:layout-justify-content values)
        set-justify-content (fn [value]
                              (st/emit! (dwsl/update-layout ids {:layout-justify-content value})))

        ;; Align content

        align-content         (:layout-align-content values)
        set-align-content     (fn [value]
                                (if (= align-content value)
                                  (st/emit! (dwsl/update-layout ids {:layout-align-content :stretch}))
                                  (st/emit! (dwsl/update-layout ids {:layout-align-content value}))))

        ;; Gap

        gap-selected?       (mf/use-state :none)

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

        ;; Grid-direction

        saved-grid-dir (:layout-grid-dir values)

        set-direction
        (fn [dir type]
          (if (= :flex type)
            (st/emit! (dwsl/update-layout ids {:layout-flex-dir dir}))
            (st/emit! (dwsl/update-layout ids {:layout-grid-dir dir}))))

        ;; Align grid
        align-items-row    (:layout-align-items values)
        align-items-column (:layout-justify-items values)

        set-align-grid
        (fn [value type]
          (if (= type :row)
            (st/emit! (dwsl/update-layout ids {:layout-align-items value}))
            (st/emit! (dwsl/update-layout ids {:layout-justify-items value}))))

        ;; Justify grid
        grid-justify-content-row    (:layout-align-content values)
        grid-justify-content-column (:layout-justify-content values)

        set-justify-grid
        (mf/use-callback
         (mf/deps ids)
         (fn [value type]
           (if (= type :row)
             (st/emit! (dwsl/update-layout ids {:layout-align-content value}))
             (st/emit! (dwsl/update-layout ids {:layout-justify-content value})))))


        ;;Grid columns
        column-grid-values  (:layout-grid-columns values)
        grid-columns-open?  (mf/use-state false)
        toggle-columns-info (mf/use-callback
                             (fn [_]
                               (swap! grid-columns-open? not)))

        ; Grid rows / columns
        rows-grid-values  (:layout-grid-rows values)
        grid-rows-open?  (mf/use-state false)
        toggle-rows-info
        (mf/use-callback
         (fn [_]
           (swap! grid-rows-open? not)))

        add-new-element
        (mf/use-callback
         (mf/deps ids)
         (fn [type value]
           (st/emit! (dwsl/add-layout-track ids type value))))

        remove-element
        (mf/use-callback
         (mf/deps ids)
         (fn [type index]
           (st/emit! (dwsl/remove-layout-track ids type index))))

        set-column-value
        (mf/use-callback
         (mf/deps ids)
         (fn [type index value]
           (st/emit! (dwsl/change-layout-track ids type index {:value value}))))

        set-column-type
        (mf/use-callback
         (mf/deps ids)
         (fn [type index track-type]
           (st/emit! (dwsl/change-layout-track ids type index {:type track-type}))))]

    [:div.element-set
     [:div.element-set-title
      [:*
       [:span "Layout"]
       (if (and (not multiple) (:layout values))
         [:div.title-actions
          #_[:div.layout-btns
           [:button {:on-click set-flex
                     :class (dom/classnames
                             :active (= :flex layout-type))} "Flex"]
           [:button {:on-click set-grid
                     :class (dom/classnames
                             :active (= :grid layout-type))} "Grid"]]
          [:button.remove-layout {:on-click on-remove-layout} i/minus]]

         [:button.add-page {:on-click #(on-add-layout :flex)} i/close])]]

     (when (:layout values)
       (when (not= :multiple layout-type)
         (case layout-type
           :flex

           [:div.element-set-content.layout-menu
            [:div.layout-row
             [:div.direction-wrap.row-title "Direction"]
             [:div.btn-wrapper
              [:div.direction
               [:*
                (for [dir [:row :row-reverse :column :column-reverse]]
                  [:& direction-btn {:key (d/name dir)
                                     :dir dir
                                     :saved-dir saved-dir
                                     :set-direction #(set-direction dir :flex)
                                     :icon? true}])]]

              [:div.wrap-type
               [:& wrap-row {:wrap-type wrap-type
                             :set-wrap set-wrap}]]]]

            (when (= :wrap wrap-type)
              [:div.layout-row
               [:div.align-content.row-title "Content"]
               [:div.btn-wrapper.align-content
                [:& align-content-row {:is-col? is-col?
                                       :align-content align-content
                                       :set-align-content set-align-content}]]])

            [:div.layout-row
             [:div.align-items.row-title "Align"]
             [:div.btn-wrapper
              [:& align-row {:is-col? is-col?
                             :align-items align-items
                             :set-align set-align-items}]]]

            [:div.layout-row
             [:div.justify-content.row-title "Justify"]
             [:div.btn-wrapper.justify-content
              [:& justify-content-row {:is-col? is-col?
                                       :justify-content justify-content
                                       :set-justify set-justify-content}]]]
            [:& gap-section {:is-col? is-col?
                             :wrap-type wrap-type
                             :gap-selected? gap-selected?
                             :set-gap set-gap
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
                                     :set-direction #(set-direction dir :grid)
                                     :icon? false}])]]

              (when (= 1 (count ids))
                [:div.edit-mode
                 [:& grid-edit-mode {:id (first ids)}]])]]

            [:div.layout-row
             [:div.align-items-grid.row-title "Align"]
             [:div.btn-wrapper.align-grid
              [:& align-grid-row {:is-col? false
                                  :align-items align-items-row
                                  :set-align set-align-grid}]

              [:& align-grid-row {:is-col? true
                                  :align-items align-items-column
                                  :set-align set-align-grid}]]]

            [:div.layout-row
             [:div.jusfiy-content-grid.row-title "Justify"]
             [:div.btn-wrapper.align-grid
              [:& justify-grid-row {:is-col? true
                                    :justify-items grid-justify-content-column
                                    :set-justify set-justify-grid}]
              [:& justify-grid-row {:is-col? false
                                    :justify-items grid-justify-content-row
                                    :set-justify set-justify-grid}]]]

            [:& grid-columns-row {:is-col? true
                                  :expanded? @grid-columns-open?
                                  :toggle toggle-columns-info
                                  :column-values column-grid-values
                                  :add-new-element add-new-element
                                  :set-column-value set-column-value
                                  :set-column-type set-column-type
                                  :remove-element remove-element}]

            [:& grid-columns-row {:is-col? false
                                  :expanded? @grid-rows-open?
                                  :toggle toggle-rows-info
                                  :column-values rows-grid-values
                                  :add-new-element add-new-element
                                  :set-column-value set-column-value
                                  :set-column-type set-column-type
                                  :remove-element remove-element}]

            [:& gap-section {:is-col? is-col?
                             :wrap-type wrap-type
                             :gap-selected? gap-selected?
                             :set-gap set-gap
                             :gap-value (:layout-gap values)}]

            [:& padding-section {:values values
                                 :on-change-style change-padding-type
                                 :on-change on-padding-change}]]


           ;; Default if not grid or flex
           nil)))]))
