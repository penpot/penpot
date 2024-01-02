;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [get-layout-flex-icon]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layout-item-attrs
  [:layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
   :layout-item-margin-type ;; :simple :multiple
   :layout-item-h-sizing    ;; :fill :fix :auto
   :layout-item-v-sizing    ;; :fill :fix :auto
   :layout-item-max-h       ;; num
   :layout-item-min-h       ;; num
   :layout-item-max-w       ;; num
   :layout-item-min-w       ;; num
   :layout-item-align-self  ;; :start :end :center :stretch :baseline
   :layout-item-absolute
   :layout-item-z-index])

(mf/defc margin-section
  [{:keys [values change-margin-style on-margin-change] :as props}]

  (let [margin-type    (or (:layout-item-margin-type values) :simple)
        m1             (when (and (not (= :multiple (:layout-item-margin values)))
                                (= (dm/get-in values [:layout-item-margin :m1])
                                   (dm/get-in values [:layout-item-margin :m3])))
                         (dm/get-in values [:layout-item-margin :m1]))

        m2             (when (and (not (= :multiple (:layout-item-margin values)))
                                (= (dm/get-in values [:layout-item-margin :m2])
                                   (dm/get-in values [:layout-item-margin :m4])))
                         (dm/get-in values [:layout-item-margin :m2]))
        select-margins
        (fn [m1? m2? m3? m4?]
          (st/emit! (udw/set-margins-selected {:m1 m1? :m2 m2? :m3 m3? :m4 m4?})))

        select-margin #(select-margins (= % :m1) (= % :m2) (= % :m3) (= % :m4))]

    (mf/use-effect
     (fn []
       (fn []
         ;;on destroy component
         (select-margins false false false false))))

    [:div {:class (stl/css :margin-row)}
     [:div {:class (stl/css :inputs-wrapper)}
      (cond
        (= margin-type :simple)
        [:div {:class (stl/css :margin-simple)}
         [:div {:class (stl/css :vertical-margin)
                :title "Vertical margin"}
          [:span {:class (stl/css :icon)}
           i/margin-top-bottom-refactor]
          [:> numeric-input* {:className (stl/css :numeric-input)
                              :placeholder "--"
                              :nillable true
                              :value m1
                              :on-focus (fn [event]
                                          (select-margins true false true false)
                                          (dom/select-target event))
                              :on-change  (partial on-margin-change :simple :m1)
                              :on-blur #(select-margins false false false false)}]]

         [:div {:class (stl/css :horizontal-margin)
                :title "Horizontal margin"}
          [:span {:class (stl/css :icon)}
           i/margin-left-right-refactor]
          [:> numeric-input* {:className (stl/css :numeric-input)
                              :placeholder "--"
                              :on-focus (fn [event]
                                          (select-margins false true false true)
                                          (dom/select-target event))
                              :on-change (partial on-margin-change :simple :m2)
                              :on-blur #(select-margins false false false false)
                              :nillable true
                              :value m2}]]]

        (= margin-type :multiple)
        [:div {:class (stl/css :margin-multiple)}
         [:div {:class (stl/css :top-margin)
                :title "Top margin"}
          [:span {:class (stl/css :icon)}
           i/margin-top-refactor]
          [:> numeric-input* {:className (stl/css :numeric-input)
                              :placeholder "--"
                              :on-focus (fn [event]
                                          (select-margin :m1)
                                          (dom/select-target event))
                              :on-change (partial on-margin-change :multiple :m1)
                              :on-blur #(select-margins false false false false)
                              :nillable true
                              :value (:m1 (:layout-item-margin values))}]]
         [:div {:class (stl/css :right-margin)
                :title "Right margin"}
          [:span {:class (stl/css :icon)}
           i/margin-right-refactor]
          [:> numeric-input* {:className (stl/css :numeric-input)
                              :placeholder "--"
                              :on-focus (fn [event]
                                          (select-margin :m2)
                                          (dom/select-target event))
                              :on-change (partial on-margin-change :multiple :m2)
                              :on-blur #(select-margins false false false false)
                              :nillable true
                              :value (:m2 (:layout-item-margin values))}]]

         [:div {:class (stl/css :bottom-margin)
                :title "Bottom margin"}
          [:span {:class (stl/css :icon)}
           i/margin-bottom-refactor]
          [:> numeric-input* {:className (stl/css :numeric-input)
                              :placeholder "--"
                              :on-focus (fn [event]
                                          (select-margin :m3)
                                          (dom/select-target event))
                              :on-change (partial on-margin-change :multiple :m3)
                              :on-blur #(select-margins false false false false)
                              :nillable true
                              :value (:m3 (:layout-item-margin values))}]]
         [:div {:class (stl/css :left-margin)
                :title "Left margin"}
          [:span {:class (stl/css :icon)}
           i/margin-left-refactor]
          [:> numeric-input* {:className (stl/css :numeric-input)
                              :placeholder "--"
                              :on-focus (fn [event]
                                          (select-margin :m4)
                                          (dom/select-target event))
                              :on-change (partial on-margin-change :multiple :m4)
                              :on-blur #(select-margins false false false false)
                              :nillable true
                              :value (:m4 (:layout-item-margin values))}]]])]
     [:button {:class (stl/css-case :margin-mode true
                                    :selected (= margin-type :multiple))
               :title "Margin - multiple"
               :on-click #(change-margin-style (if (= margin-type :multiple) :simple :multiple))}
      i/margin-refactor]]))

(mf/defc element-behaviour-horizontal
  [{:keys [auto? fill? layout-item-sizing on-change] :as props}]
  [:div {:class (stl/css-case :horizontal-behaviour true
                              :one-element (and (not fill?) (not auto?))
                              :two-element (or fill? auto?)
                              :three-element (and fill? auto?))}
   [:& radio-buttons {:selected  (d/name layout-item-sizing)
                      :on-change on-change
                      :wide      true
                      :name      "flex-behaviour-h"}
    [:& radio-button {:value "fix"
                      :icon  i/fixed-width-refactor
                      :title "Fix width"
                      :id    "behaviour-h-fix"}]
    (when fill?
      [:& radio-button {:value "fill"
                        :icon  i/fill-content-refactor
                        :title "Width 100%"
                        :id    "behaviour-h-fill"}])
    (when auto?
      [:& radio-button {:value "auto"
                        :icon  i/hug-content-refactor
                        :title "Fit content"
                        :id    "behaviour-h-auto"}])]])

(mf/defc element-behaviour-vertical
  [{:keys [auto? fill? layout-item-sizing on-change] :as props}]
  [:div {:class (stl/css-case :vertical-behaviour true
                              :one-element (and (not fill?) (not auto?))
                              :two-element (or fill? auto?)
                              :three-element (and fill? auto?))}
   [:& radio-buttons {:selected  (d/name layout-item-sizing)
                      :on-change on-change
                      :wide      true
                      :name      "flex-behaviour-v"}
    [:& radio-button {:value      "fix"
                      :icon       i/fixed-width-refactor
                      :icon-class (stl/css :rotated)
                      :title      "Fix height"
                      :id         "behaviour-v-fix"}]
    (when fill?
      [:& radio-button {:value      "fill"
                        :icon       i/fill-content-refactor
                        :icon-class (stl/css :rotated)
                        :title      "Height 100%"
                        :id         "behaviour-v-fill"}])
    (when auto?
      [:& radio-button {:value      "auto"
                        :icon       i/hug-content-refactor
                        :icon-class (stl/css :rotated)
                        :title      "Fit content"
                        :id         "behaviour-v-auto"}])]])

(mf/defc element-behaviour
  [{:keys [auto?
           fill?
           layout-item-h-sizing
           layout-item-v-sizing
           on-change-behaviour-h-refactor
           on-change-behaviour-v-refactor] :as props}]
  [:div {:class (stl/css-case :behaviour-menu true
                              :wrap (and fill? auto?))}
   [:& element-behaviour-horizontal {:auto? auto?
                                     :fill? fill?
                                     :layout-item-sizing layout-item-h-sizing
                                     :on-change on-change-behaviour-h-refactor}]
   [:& element-behaviour-vertical {:auto? auto?
                                   :fill? fill?
                                   :layout-item-sizing layout-item-v-sizing
                                   :on-change on-change-behaviour-v-refactor}]])

(mf/defc align-self-row
  [{:keys [is-col? align-self on-change] :as props}]
  [:& radio-buttons {:selected (d/name align-self)
                     :on-change on-change
                     :name "flex-align-self"}
   [:& radio-button {:value "start"
                     :icon  (get-layout-flex-icon :align-self :start is-col?)
                     :title "Align self start"
                     :id     "align-self-start"}]
   [:& radio-button {:value "center"
                     :icon  (get-layout-flex-icon :align-self :center is-col?)
                     :title "Align self center"
                     :id    "align-self-center"}]
   [:& radio-button {:value "end"
                     :icon  (get-layout-flex-icon :align-self :end is-col?)
                     :title "Align self end"
                     :id    "align-self-end"}]])

(mf/defc layout-item-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "is-layout-child?" "is-grid-parent?" "is-flex-parent?"]))]}
  [{:keys [ids values is-layout-child? is-layout-container? is-grid-parent? is-flex-parent?] :as props}]

  (let [selection-parents-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        selection-parents     (mf/deref selection-parents-ref)

        is-absolute?          (:layout-item-absolute values)

        is-col? (every? ctl/col? selection-parents)

        is-layout-child? (and is-layout-child? (not is-absolute?))

        state*                 (mf/use-state true)
        open?                  (deref state*)
        toggle-content         (mf/use-fn #(swap! state* not))
        has-content?           (or is-layout-child? is-flex-parent? is-grid-parent? is-layout-container?)

        ;; Align self

        align-self         (:layout-item-align-self values)

        title
        (cond
          (and is-layout-container? (not is-layout-child?))
          "Flex board"

          is-flex-parent?
          "Flex element"

          is-grid-parent?
          "Grid element"

          :else
          "Layout element")

        set-align-self
        (mf/use-fn
         (mf/deps ids align-self)
         (fn [value]
           (if (= align-self value)
             (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
             (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self (keyword value)})))))

        ;; Margin

        change-margin-style
        (fn [type]
          (st/emit! (dwsl/update-layout-child ids {:layout-item-margin-type type})))

        on-margin-change
        (fn [type prop val]
          (cond
            (and (= type :simple) (= prop :m1))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m1 val :m3 val}}))

            (and (= type :simple) (= prop :m2))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m2 val :m4 val}}))

            :else
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {prop val}}))))

        ;; Behaviour

        on-change-behaviour
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))
                 dir (-> (dom/get-current-target event)
                         (dom/get-data "direction")
                         (keyword))]
             (if (= dir :h)
               (st/emit! (dwsl/update-layout-child ids {:layout-item-h-sizing value}))
               (st/emit! (dwsl/update-layout-child ids {:layout-item-v-sizing value}))))))

        on-change-behaviour-h
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (let [value (keyword value)]
             (st/emit! (dwsl/update-layout-child ids {:layout-item-h-sizing value})))))


        on-change-behaviour-v
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (let [value (keyword value)]
             (st/emit! (dwsl/update-layout-child ids {:layout-item-v-sizing value})))))

        ;; Size and position

        on-size-change
        (fn [measure value]
          (st/emit! (dwsl/update-layout-child ids {measure value})))

        on-change-position
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (let [value (keyword value)]
             (when (= value :static)
               (st/emit! (dwsl/update-layout-child ids {:layout-item-z-index nil})))
             (st/emit! (dwsl/update-layout-child ids {:layout-item-absolute (= value :absolute)})))))

        ;; Z Index

        on-change-z-index
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout-child ids {:layout-item-z-index value}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:& title-bar {:collapsable? has-content?
                     :collapsed?   (not open?)
                     :on-collapsed toggle-content
                     :title        title
                     :class        (stl/css-case :title-spacing-layout-element true
                                                 :title-spacing-empty (not has-content?))}]]
     (when open?
       [:div {:class (stl/css :flex-element-menu)}
        (when (or is-layout-child? is-absolute?)
          [:div {:class (stl/css :row)}
           [:div {:class (stl/css :position-options)}
            [:& radio-buttons {:selected (if is-absolute? "absolute" "static")
                               :on-change on-change-position
                               :name "layout-style"
                               :wide true}
             [:& radio-button {:value "static"
                               :id :static-position}]
             [:& radio-button {:value "absolute"
                               :id :absolute-position}]]]

           (when is-absolute?
             [:div {:class (stl/css :z-index-wrapper)
                    :title "z-index"}

              [:span {:class (stl/css :icon-text)}
               "Z"]
              [:> numeric-input*
               {:className (stl/css :numeric-input)
                :placeholder "--"
                :on-focus #(dom/select-target %)
                :on-change #(on-change-z-index %)
                :nillable true
                :value (:layout-item-z-index values)}]])])

        [:div {:class (stl/css :row)}
         [:& element-behaviour {:fill? is-layout-child?
                                :auto? is-layout-container?
                                :layout-item-v-sizing (or (:layout-item-v-sizing values) :fix)
                                :layout-item-h-sizing (or (:layout-item-h-sizing values) :fix)
                                :on-change-behaviour-h-refactor on-change-behaviour-h
                                :on-change-behaviour-v-refactor on-change-behaviour-v
                                :on-change on-change-behaviour}]]

        (when (and is-layout-child? is-flex-parent?)
          [:div {:class (stl/css :row)}
           [:& align-self-row {:is-col? is-col?
                               :align-self align-self
                               :on-changer set-align-self}]])

        (when is-layout-child?
          [:div {:class (stl/css :row)}
           [:& margin-section {:values values
                               :change-margin-style change-margin-style
                               :on-margin-change on-margin-change}]])

        (when (or (= (:layout-item-h-sizing values) :fill)
                  (= (:layout-item-v-sizing values) :fill))
          [:div {:class (stl/css :row)}
           [:div {:class (stl/css :advanced-options)}
            (when (= (:layout-item-h-sizing values) :fill)
              [:div {:class (stl/css :horizontal-fill)}
               [:div {:class (stl/css :layout-item-min-w)
                      :title (tr "workspace.options.layout-item.layout-item-min-w")}

                [:span {:class (stl/css :icon-text)}
                 "MIN W"]
                [:> numeric-input*
                 {:className (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :on-focus #(dom/select-target %)
                  :on-change (partial on-size-change :layout-item-min-w)
                  :value (get values :layout-item-min-w)
                  :nillable true}]]

               [:div {:class (stl/css :layout-item-max-w)
                      :title (tr "workspace.options.layout-item.layout-item-max-w")}
                [:span {:class (stl/css :icon-text)}
                 "MAX W"]
                [:> numeric-input*
                 {:className (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :on-focus #(dom/select-target %)
                  :on-change (partial on-size-change :layout-item-max-w)
                  :value (get values :layout-item-max-w)
                  :nillable true}]]])
            (when (= (:layout-item-v-sizing values) :fill)
              [:div {:class (stl/css :vertical-fill)}
               [:div {:class (stl/css :layout-item-min-h)
                      :title (tr "workspace.options.layout-item.layout-item-min-h")}

                [:span {:class (stl/css :icon-text)}
                 "MIN H"]
                [:> numeric-input*
                 {:className (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :on-focus #(dom/select-target %)
                  :on-change (partial on-size-change :layout-item-min-h)
                  :value (get values :layout-item-min-h)
                  :nillable true}]]

               [:div {:class (stl/css :layout-item-max-h)
                      :title (tr "workspace.options.layout-item.layout-item-max-h")}

                [:span {:class (stl/css :icon-text)}
                 "MAX H"]
                [:> numeric-input*
                 {:className (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :on-focus #(dom/select-target %)
                  :on-change (partial on-size-change :layout-item-max-h)
                  :value (get values :layout-item-max-h)
                  :nillable true}]]])]])])]))
