;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.layout-container
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def layout-container-attrs
  [:layout                 ;; true if active, false if not
   :layout-dir             ;; :right, :left, :top, :bottom
   :layout-gap             ;; number could be negative
   :layout-type            ;; :packed, :space-between, :space-around
   :layout-wrap-type       ;; :wrap, :no-wrap
   :layout-padding-type    ;; :simple, :multiple
   :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative
   :layout-h-orientation   ;; :top, :center, :bottom
   :layout-v-orientation]) ;; :left, :center, :right

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

(defn- get-layout-icon
  [dir layout-type v h]
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

(mf/defc direction-row
  [{:keys [dir saved-dir set-direction] :as props}]
  (let [handle-on-click
        (mf/use-callback
         (mf/deps set-direction dir)
         (fn []
           (when (some? set-direction)
             (set-direction dir))))]

    [:button.dir.tooltip.tooltip-bottom
     {:class  (dom/classnames :active (= saved-dir dir)
                              :left   (= :left dir)
                              :right  (= :right dir)
                              :top    (= :top dir)
                              :bottom (= :bottom dir))
      :key    (dm/str  "direction-" dir)
      :alt    (tr (dm/str "workspace.options.layout.direction." (d/name dir)))
      :on-click handle-on-click}
     i/auto-direction]))

(mf/defc orientation-grid
  [{:keys [on-change-orientation values] :as props}]
  (let [dir       (:layout-dir values)
        type      (:layout-type values)
        is-col?   (or (= dir :top)
                      (= dir :bottom))
        saved-pos [(:layout-h-orientation values) (:layout-v-orientation values)]]

    (if (= type :packed)
      [:div.orientation-grid
       [:div.button-wrapper
        (for [[pv ph] grid-pos]
          [:button.orientation
           {:on-click (partial on-change-orientation pv ph type)
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
            (get-layout-icon dir type pv ph)]])]]

      (if is-col?
        [:div.orientation-grid.col
         [:div.button-wrapper
          (for [[idx col] (d/enumerate grid-cols)]
            [:button.orientation
             {:key (dm/str idx col)
              :on-click (partial on-change-orientation :top col type)
              :class  (dom/classnames
                       :active   (= col (second saved-pos))
                       :top      (= :left col)
                       :centered (= :center col)
                       :bottom   (= :right col))}
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-layout-icon dir type nil col)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-layout-icon dir type nil col)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-layout-icon dir type nil col)]])]]

        [:div.orientation-grid.row
         [:div.button-wrapper
          (for [row grid-rows]
            [:button.orientation
             {:on-click (partial on-change-orientation row :left type)
              :class    (dom/classnames
                         :active   (= row (first saved-pos))
                         :top      (= :top row)
                         :centered (= :center row)
                         :bottom   (= :bottom row))}
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-layout-icon dir type row nil)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-layout-icon dir type row nil)]
             [:span.icon
              {:class (dom/classnames :rotated is-col?)}
              (get-layout-icon dir type row nil)]])]]))))

(mf/defc padding-section
  [{:keys [values on-change-style on-change] :as props}]

  (let [padding-type (:layout-padding-type values)]

    [:div.row-flex
     [:div.padding-options
      [:div.padding-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= padding-type :simple))
        :alt (tr "workspace.options.layout.padding-simple")
        :on-click #(on-change-style :simple)}
       i/auto-padding]
      [:div.padding-icon.tooltip.tooltip-bottom
       {:class (dom/classnames :selected (= padding-type :multiple))
        :alt (tr "workspace.options.layout.padding")
        :on-click #(on-change-style :multiple)}
       i/auto-padding-side]]

     (cond
       (= padding-type :simple)
       [:div.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.layout.padding-all")}
        [:div.input-element.mini

         [:> numeric-input
          {:placeholder "--"
           :on-click #(dom/select-target %)
           :on-change (partial on-change :simple)
           :value (:p1 (:layout-padding values))}]]]

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
             :on-click #(dom/select-target %)
             :on-change (partial on-change num)
             :value (num (:layout-padding values))}]]]))]))

(mf/defc layout-container-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type"]))]}
  [{:keys [ids type values] :as props}]
  (let [open?             (mf/use-state false)
        gap-selected?     (mf/use-state false)
        toggle-open       (fn [] (swap! open? not))

        on-add-layout
        (fn [_]
          (st/emit! (dwsl/create-layout ids)))

        on-remove-layout
        (fn [_]
          (st/emit! (dwsl/remove-layout ids))
          (reset! open? false))

        set-direction
        (fn [dir]
          (st/emit! (dwsl/update-layout ids {:layout-dir dir})))

        set-gap
        (fn [gap]
          (st/emit! (dwsl/update-layout ids {:layout-gap gap})))
        
        change-padding-style
        (fn [type]
          (st/emit! (dwsl/update-layout ids {:layout-padding-type type})))

        select-all #(dom/select-target %)

        select-all-gap
        (fn [event]
          (reset! gap-selected? true)
          (dom/select-target event))

        on-padding-change
        (fn [type val]
          (if (= type :simple)
            (st/emit! (dwsl/update-layout ids {:layout-padding {:p1 val :p2 val :p3 val :p4 val}}))
            (st/emit! (dwsl/update-layout ids {:layout-padding {type val}}))))

        handle-change-type
        (fn [event]
          (let [target  (dom/get-target event)
                value   (dom/get-value target)
                value   (keyword value)]
            (st/emit! (dwsl/update-layout ids {:layout-type value}))))

        handle-wrap-type
        (mf/use-callback
         (fn [event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (keyword value)]
             (st/emit! (dwsl/update-layout ids {:layout-wrap-type value})))))

        handle-change-orientation
        (fn [h-orientation v-orientation]
          (st/emit! (dwsl/update-layout ids {:layout-h-orientation h-orientation :layout-v-orientation v-orientation})))

        layout-info
        (fn []
          (let [type        (:layout-type values)
                dir         (:layout-dir values)
                is-col?     (or (= dir :top) (= dir :bottom))
                h           (:layout-v-orientation values)
                v           (:layout-h-orientation values)
                wrap        (:layout-wrap-type values)

                orientation
                (cond
                  (= type :packaged)
                  (dm/str (tr (dm/str "workspace.options.layout.v." (d/name v))) ", "
                          (tr (dm/str "workspace.options.layout.h." (d/name h))) ", ")

                  is-col?
                  (dm/str (tr (dm/str "workspace.options.layout.h." (d/name h))) ", ")

                  :else
                  (dm/str (tr (dm/str "workspace.options.layout.v." (d/name v)))  ", "))]

            (dm/str orientation
                    (str/replace (tr (dm/str "workspace.options.layout." (d/name type))) "-" " ") ", "
                    (str/replace (tr (dm/str "workspace.options.layout." (d/name wrap))) "-" " "))))]

    [:div.element-set
     [:div.element-set-title
      [:*
       [:span (tr "workspace.options.layout.title")]
       (if (:layout values)
         [:div.add-page {:on-click on-remove-layout} i/minus]
         [:div.add-page {:on-click on-add-layout} i/close])]]

     (when (:layout values)
       [:div.element-set-content.layout-menu
        ;; DIRECTION-GAP
        [:div.direction-gap
         [:div.direction
          [:*
           (for [dir [:left :right :bottom :top]]
             [:& direction-row {:dir dir
                                :saved-dir (:layout-dir values)
                                :set-direction set-direction}])]]
         [:div.gap.tooltip.tooltip-bottom-left
          {:alt (tr "workspace.options.layout.gap")}
          [:span.icon
           {:class (dom/classnames
                    :rotated (or (= (:layout-dir values) :top)
                                 (= (:layout-dir values) :bottom))
                    :activated (= @gap-selected? true))}
           i/auto-gap]
          [:> numeric-input {:no-validate true
                             :placeholder "--"
                             :on-click select-all-gap
                             :on-change set-gap
                             :on-blur #(reset! gap-selected? false)
                             :value (:layout-gap values)}]]]

        ;; LAYOUT FLEX
        [:div.layout-container
         [:div.layout-entry.tooltip.tooltip-bottom
          {:on-click toggle-open
           :alt (layout-info)}
          [:div.element-set-actions-button i/actions]
          [:div.layout-info (layout-info)]]

         (when @open?
           [:div.layout-body
            [:& orientation-grid {:on-change-orientation handle-change-orientation :values values}]

            [:div.selects-wrapper
             [:select.input-select {:value (d/name (:layout-type values))
                                    :on-change handle-change-type}
              [:option {:value "packed" :label (tr "workspace.options.layout.packed")}]
              [:option {:value "space-between" :label (tr "workspace.options.layout.space-between")}]
              [:option {:value "space-around" :label (tr "workspace.options.layout.space-around")}]]

             [:select.input-select {:value (d/name (:layout-wrap-type values))
                                    :on-change handle-wrap-type}
              [:option {:value "wrap" :label (tr "workspace.options.layout.wrap")}]
              [:option {:value "no-wrap" :label (tr "workspace.options.layout.no-wrap")}]]]])]

        [:& padding-section {:values values
                             :on-change-style change-padding-style
                             :on-change on-padding-change}]])]))
