;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.layout
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def layout-attrs
  [:layout          ;; true if active, false if not
   :layout-dir      ;; :right, :left, :top, :bottom
   :gap             ;; number could be negative
   :layout-type     ;; :packed, :space-between, :space-around
   :wrap-type       ;; :wrap, :no-wrap
   :padding-type    ;; :simple, :multiple
   :padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative
   :h-orientation   ;; :top, :center, :bottom
   :v-orientation]) ;; :left, :center, :right

(def grid-pos  [[:top :left]
                [:top :center]
                [:top :right]
                [:center :left]
                [:center :center]
                [:center :right]
                [:bottom :left]
                [:bottom :center]
                [:bottom :right]])
(def grid-rows [:top :center :bottom])
(def grid-cols [:left :center :right])

(mf/defc direction-row
  [{:keys [dir saved-dir set-direction] :as props}]
  [:button.dir.tooltip.tooltip-bottom
   {:class  (dom/classnames :active (= saved-dir dir)
                            :left   (= :left dir)
                            :right  (= :right dir)
                            :top    (= :top dir)
                            :bottom (= :bottom dir))
    :key    (dm/str  "direction-" dir)
    :alt    (tr (dm/str "workspace.options.layout.direction." (d/name dir)))
    :on-click #(set-direction dir)}
   i/auto-direction])

(mf/defc orientation-grid
  [{:keys [manage-orientation test-values get-icon] :as props}]
  (let [dir       (:layout-dir @test-values)
        type      (:layout-type @test-values)
        is-col?   (or (= dir :top)
                      (= dir :bottom))
        saved-pos [(:h-orientation @test-values) (:v-orientation @test-values)]]
    (if (= type :packed)
      [:div.orientation-grid
       [:div.button-wrapper
        (for [[pv ph] grid-pos]
          [:button.orientation
           {:on-click (partial manage-orientation pv ph type)
            :class  (dom/classnames
                     :active (= [pv ph] saved-pos)
                     :top    (= :top pv)
                     :center (= :center pv)
                     :bottom (= :bottom pv)
                     :left   (= :left ph)
                     :center (= :center ph)
                     :right  (= :right ph))
            :key    (dm/str pv ph)}
           [:span.icon
            {:class (dom/classnames
                     :rotated is-col?)}
            (get-icon dir type pv ph)]])]]

      (if is-col?
        [:div.orientation-grid.col
         [:div.button-wrapper
          (for [col grid-cols]
            [:button.orientation
             {:on-click (partial manage-orientation :top col type)
              :class  (dom/classnames
                       :active   (= col (second saved-pos))
                       :top      (= :left col)
                       :centered (= :center col)
                       :bottom   (= :right col))}
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-icon dir type nil col)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-icon dir type nil col)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-icon dir type nil col)]])]]

        [:div.orientation-grid.row
         [:div.button-wrapper
          (for [row grid-rows]
            [:button.orientation
             {:on-click (partial manage-orientation row :left type)
              :class    (dom/classnames
                         :active   (= row (first saved-pos))
                         :top      (= :top row)
                         :centered (= :center row)
                         :bottom   (= :bottom row))}
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-icon dir type row nil)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-icon dir type row nil)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-icon dir type row nil)]])]]))))

(mf/defc padding-section
  [{:keys [test-values change-padding-style select-all on-padding-change] :as props}]

  (let [padding-type (:padding-type @test-values)]

    [:div.row-flex
     [:div.padding-options
      [:div.padding-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= padding-type :simple))
        :alt (tr "workspace.options.layout.padding-simple")
        :on-click #(change-padding-style :simple)}
       i/auto-padding]
      [:div.padding-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= padding-type :multiple))
        :alt (tr "workspace.options.layout.padding")
        :on-click #(change-padding-style :multiple)}
       i/auto-padding-side]]

     (cond
       (= padding-type :simple)
       [:div.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.layout.padding-all")}
        [:div.input-element.mini

         [:> numeric-input
          {:placeholder "--"
           :on-click select-all
           :on-change (partial on-padding-change :simple)
           :value (:p1 (:padding @test-values))}]]]

       (= padding-type :multiple)
       (for [num [:p1 :p2 :p3 :p4]]
         [:div.tooltip.tooltip-bottom
          {:key (dm/str "padding-" (d/name num))
           :alt (case num
                  :p1 (tr "workspace.options.layout.top")
                  :p2 (tr "workspace.options.layout.right")
                  :p3 (tr "workspace.options.layout.bottom")
                  :p4 (tr "workspace.options.layout.left"))}
          [:div.input-element.mini
           [:> numeric-input
            {:placeholder "--"
             :on-click select-all
             :on-change (partial on-padding-change num)
             :value (num (:padding @test-values))}]]]))]))



(mf/defc layout-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [_ids _type _values] :as props}]
  (let [test-values       (mf/use-state {:layout false
                                         :layout-dir nil
                                         :gap 0
                                         :layout-type nil
                                         :wrap-type nil
                                         :padding-type nil
                                         :padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                         :h-orientation nil
                                         :v-orientation nil})

        open?             (mf/use-state false)
        gap-selected?     (mf/use-state false)
        toggle-open       (fn [] (swap! open? not))

        on-add-layout
        (fn [_]
          (reset! test-values {:layout true
                               :layout-dir :left
                               :gap 0
                               :layout-type :packed
                               :wrap-type :wrap
                               :padding-type :simple
                               :padding {:p1 0 :p2 0 :p3 0 :p4 0}
                               :h-orientation :top
                               :v-orientation :left}))
        on-remove-layout
        (fn [_]
          (reset! test-values {:layout false
                               :layout-dir nil
                               :gap 0
                               :layout-type nil
                               :wrap-type nil
                               :padding-type nil
                               :padding {:p1 0 :p2 0 :p3 0 :p4 0}
                               :h-orientation nil
                               :v-orientation nil})
          (reset! open? false))

        set-direction
        (fn [dir]
          (swap! test-values assoc :layout-dir dir))

        set-gap
        (fn [event]
          (swap! test-values assoc :gap event))

        change-padding-style
        (fn [type]
          (swap! test-values assoc :padding-type type))

        select-all #(dom/select-target %)

        select-all-gap #(do (reset! gap-selected? true)
                            (dom/select-target %))

        on-padding-change
        (fn [type val]
          (if (= type :simple)
            (swap! test-values assoc :padding {:p1 val :p2 val :p3 val :p4 val})
            (swap! test-values assoc-in [:padding type] val)))

        handle-change-type
        (fn [event]
          (let [target  (dom/get-target event)
                value   (dom/get-value target)
                value   (keyword value)]
            (swap! test-values assoc :layout-type value)))

        handle-wrap-type
        (mf/use-callback
         (fn [event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (keyword value)]
             (swap! test-values assoc :wrap-type value))))

        manage-orientation
        (fn [h v]
          (swap! test-values assoc :h-orientation h :v-orientation v))

        get-icon
        (fn [dir layout-type v h]
          (let [col? (= dir (or :left :right))
                manage-text-icon
                (if col?
                  (case h
                    :left   i/text-align-left
                    :center i/text-align-center
                    :right  i/text-align-right
                    i/text-align-center)

                  (case v
                    :top    i/text-align-left
                    :center i/text-align-center
                    :bottom i/text-align-right
                    i/text-align-center))]
            (case layout-type
              :packed        manage-text-icon
              :space-around  i/space-around
              :space-between i/space-between)))

        layout-info
        (fn []
          (let [type        (:layout-type @test-values)
                dir         (:layout-dir @test-values)
                is-col?     (or (= dir :top)
                                (= dir :bottom))
                h           (:v-orientation @test-values)
                v           (:h-orientation @test-values)
                wrap        (:wrap-type @test-values)
                orientation (if (= type :packed)
                              (dm/str (tr (dm/str "workspace.options.layout.v." (d/name v))) ", " (tr (dm/str "workspace.options.layout.h." (d/name h))) ", ")
                              (if is-col?
                                (dm/str (tr (dm/str "workspace.options.layout.h." (d/name h))) ", ")
                                (dm/str (tr (dm/str "workspace.options.layout.v." (d/name v)))  ", ")))]

            (dm/str orientation
                    (str/replace (tr (dm/str "workspace.options.layout." (d/name type))) "-" " ") ", "
                    (str/replace (tr (dm/str "workspace.options.layout." (d/name wrap))) "-" " "))))]

    [:div.element-set
     [:div.element-set-title
      [:*
       [:span (tr "workspace.options.layout.title")]
       (if (= true (:layout @test-values))
         [:div.add-page {:on-click on-remove-layout} i/minus]
         [:div.add-page {:on-click on-add-layout} i/close])]]

     (when (= true (:layout @test-values))
       [:div.element-set-content.layout-menu
        ;; DIRECTION-GAP
        [:div.direction-gap
         [:div.direction
          [:*
           (for [dir [:left :right :bottom :top]]
             [:& direction-row {:dir dir
                                :saved-dir (:layout-dir @test-values)
                                :set-direction set-direction}])]]
         [:div.gap.tooltip.tooltip-bottom-left
          {:alt (tr "workspace.options.layout.gap")}
          [:span.icon
           {:class (dom/classnames
                    :rotated (or (= (:layout-dir @test-values) :top)
                                 (= (:layout-dir @test-values) :bottom))
                    :activated (= @gap-selected? true))}
           i/auto-gap]
          [:> numeric-input {:no-validate true
                             :placeholder "--"
                             :on-click select-all-gap
                             :on-change set-gap
                             :on-blur #(reset! gap-selected? false)
                             :value (:gap @test-values)}]]]

        ;; LAYOUT FLEX
        [:div.layout-container
         [:div.layout-entry.tooltip.tooltip-bottom
          {:on-click toggle-open
           :alt (layout-info)}
          [:div.element-set-actions-button i/actions]
          [:div.layout-info
           (layout-info)]]
         (when (= true @open?)
           [:div.layout-body
            [:& orientation-grid {:manage-orientation  manage-orientation :test-values test-values :get-icon get-icon}]

            [:div.selects-wrapper
             [:select.input-select {:value (d/name (:layout-type @test-values))
                                    :on-change handle-change-type}
              [:option {:value "packed" :label (tr "workspace.options.layout.packed")}]
              [:option {:value "space-between" :label (tr "workspace.options.layout.space-between")}]
              [:option {:value "space-around" :label (tr "workspace.options.layout.space-around")}]]

             [:select.input-select {:value (d/name (:wrap-type @test-values))
                                    :on-change handle-wrap-type}
              [:option {:value "wrap" :label (tr "workspace.options.layout.wrap")}]
              [:option {:value "no-wrap" :label (tr "workspace.options.layout.no-wrap")}]]]])]

        [:& padding-section {:test-values test-values
                             :change-padding-style change-padding-style
                             :select-all select-all
                             :on-padding-change on-padding-change}]])]))
