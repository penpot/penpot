;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-container
  (:require [app.common.data :as d]
            [app.common.data.macros :as dm]
            [app.main.data.workspace.shape-layout :as dwsl]
            [app.main.store :as st]
            [app.main.ui.components.numeric-input :refer [numeric-input]]
            [app.main.ui.icons :as i]
            [app.util.dom :as dom]
            [app.util.i18n :as i18n :refer [tr]]
            [cuerdas.core :as str]
            [rumext.v2 :as mf]))

(def layout-container-flex-attrs
  [:layout                 ;; :flex, :grid in the future
   :layout-flex-dir        ;; :row, :reverse-row, :column, :reverse-column 
   :layout-gap-type        ;; :simple, :multiple
   :layout-gap             ;; {:row-gap number , :column-gap number}
   :layout-align-items     ;; :start :end :center :strech
   :layout-justify-content ;; :start :center :end :space-between :space-around
   :layout-align-content   ;; :start :center :end :space-between :space-around :strech (by default)
   :layout-wrap-type       ;; :wrap, :no-wrap
   :layout-padding-type    ;; :simple, :multiple
   :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative
   ])

(defn get-layout-flex-icon
  [type val is-col?]
  (case type
    :align-items (if is-col?
                   (case val
                     :start    i/align-items-column-start
                     :end      i/align-items-column-end
                     :center   i/align-items-column-center
                     :strech   i/align-items-column-strech
                     :baseline i/align-items-column-baseline)
                   (case val
                     :start    i/align-items-row-start
                     :end      i/align-items-row-end
                     :center   i/align-items-row-center
                     :strech   i/align-items-row-strech
                     :baseline i/align-items-row-baseline))
    :justify-content (if is-col?
                       (case val
                         :start         i/justify-content-column-start
                         :end           i/justify-content-column-end
                         :center        i/justify-content-column-center
                         :space-around  i/justify-content-column-around
                         :space-between i/justify-content-column-between)
                       (case val
                         :start         i/justify-content-row-start
                         :end           i/justify-content-row-end
                         :center        i/justify-content-row-center
                         :space-around  i/justify-content-row-around
                         :space-between i/justify-content-row-between))

    :align-content  (if is-col?
                      (case val
                        :start         i/align-content-column-start
                        :end           i/align-content-column-end
                        :center        i/align-content-column-center
                        :space-around  i/align-content-column-around
                        :space-between i/align-content-column-between
                        :strech nil)
                      
                      (case val
                        :start         i/align-content-row-start
                        :end           i/align-content-row-end
                        :center        i/align-content-row-center
                        :space-around  i/align-content-row-around
                        :space-between i/align-content-row-between
                        :strech nil))

    :align-self  (if is-col?
                   (case val
                     :start     i/align-self-column-top
                     :end   i/align-self-column-bottom
                     :center   i/align-self-column-center
                     :strech   i/align-self-column-strech
                     :baseline i/align-self-column-baseline)
                   (case val
                     :start   i/align-self-row-left
                     :end    i/align-self-row-right
                     :center   i/align-self-row-center
                     :strech   i/align-self-row-strech
                     :baseline i/align-self-row-baseline))))

(mf/defc direction-btn
  [{:keys [dir saved-dir set-direction] :as props}]
  (let [handle-on-click
        (mf/use-callback
         (mf/deps set-direction dir)
         (fn []
           (when (some? set-direction)
             (set-direction dir))))]

    [:button.dir.tooltip.tooltip-bottom
     {:class  (dom/classnames :active         (= saved-dir dir)
                              :row            (= :row dir)
                              :reverse-row    (= :reverse-row dir)
                              :reverse-column (= :reverse-column dir)
                              :column         (= :column dir))
      :key    (dm/str  "direction-" dir)
      :alt    (str/replace (str/capital (d/name dir)) "-" " ")
      :on-click handle-on-click}
     i/auto-direction]))

(mf/defc wrap-row
  [{:keys [wrap-type set-wrap] :as props}]
  [:*
   [:button.tooltip.tooltip-bottom
    {:class  (dom/classnames :active  (= wrap-type :no-wrap))
     :alt    "No-wrap"
     :on-click #(set-wrap :no-wrap)
     :style {:padding 0}}
    [:span.no-wrap i/minus]]
   [:button.wrap.tooltip.tooltip-bottom
    {:class  (dom/classnames :active  (= wrap-type :wrap))
     :alt    "wrap"
     :on-click #(set-wrap :wrap)}
    i/auto-wrap]])

(mf/defc align-row
  [{:keys [is-col? align-items set-align] :as props}]

  [:div.align-items-style
   (for [align [:start :center :end :strech]]
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
  [:div.align-content-style
   (for [align [:start :center :end :space-around :space-between]]
     [:button.align-content.tooltip
      {:class    (dom/classnames :active  (= align-content align)
                                 :tooltip-bottom-left (not= align :start)
                                 :tooltip-bottom (= align :start))
       :alt      (dm/str "Align content " (d/name align))
       :on-click #(set-align-content align)
       :key      (dm/str  "align-content" (d/name align))}
      (get-layout-flex-icon :align-content align is-col?)])])

(mf/defc justify-content-row
  [{:keys [is-col? justify-content set-justify] :as props}]
  [:div.justify-content-style
   (for [justify [:start :center :end :space-around :space-between]]
     [:button.justify.tooltip
      {:class    (dom/classnames :active  (= justify-content justify)
                                 :tooltip-bottom-left (not= justify :start)
                                 :tooltip-bottom (= justify :start))
       :alt      (dm/str "Justify content " (d/name justify))
       :on-click #(set-justify justify)
       :key (dm/str "justify-content" (d/name justify))}
      (get-layout-flex-icon :justify-content justify is-col?)])])


(mf/defc padding-section
  [{:keys [values on-change-style on-change] :as props}]

  (let [padding-type (:layout-padding-type values)
        rx (if (apply = (vals (:layout-padding values)))
             (:p1 (:layout-padding values))
             "--")]

    [:div.padding-row
     [:div.padding-icons
      [:div.padding-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= padding-type :simple))
        :alt "Padding"
        :on-click #(on-change-style :simple)}
       i/auto-padding]
      [:div.padding-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= padding-type :multiple))
        :alt "Padding - sides"
        :on-click #(on-change-style :multiple)}
       i/auto-padding-side]]
     [:div.wrapper
      (cond
        (= padding-type :simple)
        [:div.tooltip.tooltip-bottom
         {:alt (tr "workspace.options.layout.padding-all")}
         [:div.input-element.mini

          [:> numeric-input
           {:placeholder "--"
            :on-click #(dom/select-target %)
            :on-change (partial on-change :simple)
            :value rx}]]]

        (= padding-type :multiple)
        (for [num [:p1 :p2 :p3 :p4]]
          [:div.tooltip.tooltip-bottom
           {:key (dm/str "padding-" (d/name num))
            :alt (case num
                   :p1 "Top"
                   :p2 "Right"
                   :p3 "Bottom"
                   :p4 "Left")}
           [:div.input-element.mini
            [:> numeric-input
             {:placeholder "--"
              :on-click #(dom/select-target %)
              :on-change (partial on-change num)
              :value (num (:layout-padding values))}]]]))]]))

(mf/defc gap-section
  [{:keys [gap-selected? set-gap gap-value toggle-gap-type gap-type]}]
  (let [gap-locked?          (= gap-type :simple)
        some-gap-selected    (not= @gap-selected? :none)
        is-row-activated?    (or (and (not gap-locked?) (= @gap-selected? :row-gap)) (and gap-locked? some-gap-selected))
        is-column-activated? (or (and (not gap-locked?) (= @gap-selected? :column-gap)) (and gap-locked? some-gap-selected))]

    [:div.gap-group
     [:div.gap-row.tooltip.tooltip-bottom-left
      {:alt "Row gap"}
      [:span.icon
       {:class (dom/classnames :activated is-row-activated?)}
       i/auto-gap]
      [:> numeric-input {:no-validate true
                         :placeholder "--"
                         :on-click (fn [event]
                                     (reset! gap-selected? :row-gap)
                                     (dom/select-target event))
                         :on-change (partial set-gap gap-locked? :row-gap)
                         :on-blur #(reset! gap-selected? :none)
                         :value (:row-gap gap-value)}]]

     [:div.gap-column.tooltip.tooltip-bottom-left
      {:alt "Column gap"}
      [:span.icon.rotated
       {:class (dom/classnames
                :activated is-column-activated?)}
       i/auto-gap]
      [:> numeric-input {:no-validate true
                         :placeholder "--"
                         :on-click (fn [event]
                                     (reset! gap-selected? :column-gap)
                                     (dom/select-target event))
                         :on-change (partial set-gap gap-locked? :column-gap)
                         :on-blur #(reset! gap-selected? :none)
                         :value (:column-gap gap-value)}]]
     [:button.lock {:on-click toggle-gap-type
                    :class (dom/classnames :active gap-locked?)}
      (if gap-locked?
        i/lock
        i/unlock)]]))

(mf/defc layout-container-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [ids _type values] :as props}]
  (let [open?               (mf/use-state false)

        ;; Display
        layout-type         (:layout values)

        on-add-layout
        (fn [type]
          (st/emit! (dwsl/create-layout ids type)))

        on-remove-layout
        (fn [_]
          (st/emit! (dwsl/remove-layout ids))
          (reset! open? false))

        set-flex            (fn []
                              (st/emit! (dwsl/remove-layout ids))
                              (on-add-layout :flex))

        set-grid            (fn []
                              (st/emit! (dwsl/remove-layout ids))
                              (on-add-layout :grid))

        ;; Flex-direction

        saved-dir (:layout-flex-dir values)
        is-col? (or (= :column saved-dir) (= :reverse-column saved-dir))
        set-direction
        (fn [dir]
          (st/emit! (dwsl/update-layout ids {:layout-flex-dir dir})))

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
                                  (st/emit! (dwsl/update-layout ids {:layout-align-content :strech}))
                                  (st/emit! (dwsl/update-layout ids {:layout-align-content value}))))

        ;; Gap

        change-gap-type
        (fn [type]
          (st/emit! (dwsl/update-layout ids {:layout-gap-type type})))

        gap-type (:layout-gap-type values)

        gap-selected?       (mf/use-state :none)
        gap-locked?         (= gap-type :simple)
        toggle-gap-type     (fn []
                              (let [layout-gap (:layout-gap values)
                                    row-gap (:row-gap layout-gap)
                                    column-gap (:column-gap layout-gap)
                                    max (max row-gap column-gap)
                                    new-type (if (= gap-type :simple)
                                               :multiple
                                               :simple)]
                                (when (and (not= row-gap column-gap) (= gap-type :multiple))
                                  (st/emit! (dwsl/update-layout ids {:layout-gap {:row-gap max :column-gap max}})))
                                (change-gap-type new-type)))
        set-gap
        (fn [gap-locked? type val]
          (if gap-locked?
            (st/emit! (dwsl/update-layout ids {:layout-gap {:row-gap val :column-gap val}}))
            (st/emit! (dwsl/update-layout ids {:layout-gap {type val}}))))

        select-all-gap
        (fn [event]
          (when gap-locked?
            (dom/select-target event)))

        ;; Padding

        change-padding-type
        (fn [type]
          (st/emit! (dwsl/update-layout ids {:layout-padding-type type})))

        on-padding-change
        (fn [type val]
          (if (= type :simple)
            (st/emit! (dwsl/update-layout ids {:layout-padding {:p1 val :p2 val :p3 val :p4 val}}))
            (st/emit! (dwsl/update-layout ids {:layout-padding {type val}}))))]

    [:div.element-set
     [:div.element-set-title
      [:*
       [:span "Layout"]
       (if (:layout values)
         [:div.title-actions
          [:div.layout-btns
           [:button {:on-click set-flex
                     :class (dom/classnames
                             :active (= :flex layout-type))} "Flex"]
           [:button {:on-click set-grid
                     :class (dom/classnames
                             :active (= :grid layout-type))} "Grid"]]
          [:button.remove-layout {:on-click on-remove-layout} i/minus]]

         [:button.add-page {:on-click #(on-add-layout :flex)} i/close])]]

     (when (:layout values)
       (if (= :flex layout-type)
         [:div.element-set-content.layout-menu
          [:div.layout-row
           [:div.direction-wrap.row-title "Direction"]
           [:div.btn-wrapper
            [:div.direction
             [:*
              (for [dir [:row :reverse-row :column :reverse-column]]
                [:& direction-btn {:key (d/name dir)
                                   :dir dir
                                   :saved-dir saved-dir
                                   :set-direction set-direction}])]]

            [:div.wrap-type
             [:& wrap-row {:wrap-type wrap-type
                           :set-wrap set-wrap}]]]]

          (when (= :wrap wrap-type)
            [:div.layout-row
             [:div.align-content.row-title "Content"]
             [:div.btn-wrapper
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
           [:div.btn-wrapper
            [:& justify-content-row {:is-col? is-col?
                                     :justify-content justify-content
                                     :set-justify set-justify-content}]]]
          [:& gap-section {:gap-selected? gap-selected?
                           :select-all-gap select-all-gap
                           :set-gap set-gap
                           :gap-value (:layout-gap values)
                           :toggle-gap-type toggle-gap-type
                           :gap-type gap-type}]


          [:& padding-section {:values values
                               :on-change-style change-padding-type
                               :on-change on-padding-change}]]

         [:div "GRID TO COME"]))]))
