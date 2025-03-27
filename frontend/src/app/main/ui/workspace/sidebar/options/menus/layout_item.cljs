;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
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

(defn- select-margins
  [m1? m2? m3? m4?]
  (st/emit! (udw/set-margins-selected {:m1 m1? :m2 m2? :m3 m3? :m4 m4?})))

(defn- select-margin
  [prop]
  (select-margins (= prop :m1) (= prop :m2) (= prop :m3) (= prop :m4)))

(mf/defc margin-simple*
  [{:keys [value on-change on-blur]}]
  (let [m1 (:m1 value)
        m2 (:m2 value)
        m3 (:m3 value)
        m4 (:m4 value)

        m1-placeholder (if (and (not= value :multiple) (not= m1 m3)) (tr "settings.multiple") "--")
        m2-placeholder (if (and (not= value :multiple) (not= m2 m4)) (tr "settings.multiple") "--")

        m1 (when (and (not= value :multiple) (= m1 m3)) m1)
        m2 (when (and (not= value :multiple) (= m2 m4)) m2)

        on-focus
        (mf/use-fn
         (fn [event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "name")
                          (keyword))]
             (case attr
               :m1 (select-margins true false true false)
               :m2 (select-margins false true false true))

             (dom/select-target event))))

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [value event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "name")
                          (keyword))]
             (on-change :simple attr value))))]

    [:div {:class (stl/css :margin-simple)}
     [:div {:class (stl/css :vertical-margin)
            :title "Vertical margin"}
      [:span {:class (stl/css :icon)}
       i/margin-top-bottom]
      [:> numeric-input* {:class (stl/css :numeric-input)
                          :placeholder m1-placeholder
                          :data-name "m1"
                          :on-focus on-focus
                          :on-change on-change'
                          :on-blur on-blur
                          :nillable true
                          :value m1}]]

     [:div {:class (stl/css :horizontal-margin)
            :title "Horizontal margin"}
      [:span {:class (stl/css :icon)}
       i/margin-left-right]
      [:> numeric-input* {:class (stl/css :numeric-input)
                          :placeholder m2-placeholder
                          :data-name "m2"
                          :on-focus on-focus
                          :on-change on-change'
                          :on-blur on-blur
                          :nillable true
                          :value m2}]]]))

(mf/defc margin-multiple*
  [{:keys [value on-change on-blur]}]
  (let [m1     (:m1 value)
        m2     (:m2 value)
        m3     (:m3 value)
        m4     (:m4 value)

        on-focus
        (mf/use-fn
         (fn [event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "name")
                          (keyword))]
             (select-margin attr)
             (dom/select-target event))))

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [value event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "name")
                          (keyword))]
             (on-change :multiple attr value))))]

    [:div {:class (stl/css :margin-multiple)}
     [:div {:class (stl/css :top-margin)
            :title "Top margin"}
      [:span {:class (stl/css :icon)}
       i/margin-top]
      [:> numeric-input* {:class (stl/css :numeric-input)
                          :placeholder "--"
                          :data-name "m1"
                          :on-focus on-focus
                          :on-change on-change'
                          :on-blur on-blur
                          :nillable true
                          :value m1}]]
     [:div {:class (stl/css :right-margin)
            :title "Right margin"}
      [:span {:class (stl/css :icon)}
       i/margin-right]
      [:> numeric-input* {:class (stl/css :numeric-input)
                          :placeholder "--"
                          :data-name "m2"
                          :on-focus on-focus
                          :on-change on-change'
                          :on-blur on-blur
                          :nillable true
                          :value m2}]]

     [:div {:class (stl/css :bottom-margin)
            :title "Bottom margin"}
      [:span {:class (stl/css :icon)}
       i/margin-bottom]
      [:> numeric-input* {:class (stl/css :numeric-input)
                          :placeholder "--"
                          :data-name "m3"
                          :on-focus on-focus
                          :on-change on-change'
                          :on-blur on-blur
                          :nillable true
                          :value m3}]]

     [:div {:class (stl/css :left-margin)
            :title "Left margin"}
      [:span {:class (stl/css :icon)}
       i/margin-left]
      [:> numeric-input* {:class (stl/css :numeric-input)
                          :placeholder "--"
                          :data-name "m4"
                          :on-focus on-focus
                          :on-change on-change'
                          :on-blur on-blur
                          :nillable true
                          :value m4}]]]))


(mf/defc margin-section*
  {::mf/private true
   ::mf/expect-props #{:value :type :on-type-change :on-change}}
  [{:keys [type on-type-change] :as props}]
  (let [type       (d/nilv type :simple)
        on-blur    (mf/use-fn #(select-margins false false false false))
        props      (mf/spread-props props {:on-blur on-blur})

        on-type-change'
        (mf/use-fn
         (mf/deps type on-type-change)
         (fn [_]
           (if (= type :multiple)
             (on-type-change :simple)
             (on-type-change :multiple))))]

    (mf/with-effect []
      (fn [] (on-blur)))

    [:div {:class (stl/css :margin-row)}
     [:div {:class (stl/css :inputs-wrapper)}
      (cond
        (= type :simple)
        [:> margin-simple* props]

        (= type :multiple)
        [:> margin-multiple* props])]

     [:button {:class (stl/css-case
                       :margin-mode true
                       :selected (= type :multiple))
               :title "Margin - multiple"
               :on-click on-type-change'}
      i/margin]]))

(mf/defc element-behaviour-horizontal
  {::mf/props :obj
   ::mf/private true}
  [{:keys [^boolean is-auto ^boolean has-fill value on-change]}]
  [:div {:class (stl/css-case
                 :horizontal-behaviour true
                 :one-element (and (not has-fill) (not is-auto))
                 :two-element (or has-fill is-auto)
                 :three-element (and has-fill is-auto))}
   [:& radio-buttons
    {:selected  (d/name value)
     :decode-fn keyword
     :on-change on-change
     :wide      true
     :name      "flex-behaviour-h"}

    [:& radio-button
     {:value "fix"
      :icon  i/fixed-width
      :title "Fix width"
      :id    "behaviour-h-fix"}]

    (when has-fill
      [:& radio-button
       {:value "fill"
        :icon  i/fill-content
        :title "Width 100%"
        :id    "behaviour-h-fill"}])
    (when is-auto
      [:& radio-button
       {:value "auto"
        :icon  i/hug-content
        :title "Fit content (Horizontal)"
        :id    "behaviour-h-auto"}])]])

(mf/defc element-behaviour-vertical
  {::mf/props :obj
   ::mf/private true}
  [{:keys [^boolean is-auto ^boolean has-fill value on-change]}]
  [:div {:class (stl/css-case
                 :vertical-behaviour true
                 :one-element (and (not has-fill) (not is-auto))
                 :two-element (or has-fill is-auto)
                 :three-element (and has-fill is-auto))}
   [:& radio-buttons
    {:selected  (d/name value)
     :decode-fn keyword
     :on-change on-change
     :wide      true
     :name      "flex-behaviour-v"}

    [:& radio-button
     {:value      "fix"
      :icon       i/fixed-width
      :icon-class (stl/css :rotated)
      :title      "Fix height"
      :id         "behaviour-v-fix"}]

    (when has-fill
      [:& radio-button
       {:value      "fill"
        :icon       i/fill-content
        :icon-class (stl/css :rotated)
        :title      "Height 100%"
        :id         "behaviour-v-fill"}])
    (when is-auto
      [:& radio-button
       {:value      "auto"
        :icon       i/hug-content
        :icon-class (stl/css :rotated)
        :title      "Fit content (Vertical)"
        :id         "behaviour-v-auto"}])]])

(mf/defc align-self-row
  {::mf/props :obj}
  [{:keys [^boolean is-col value on-change]}]
  [:& radio-buttons {:selected (d/name value)
                     :decode-fn keyword
                     :on-change on-change
                     :name "flex-align-self"
                     :allow-empty true}
   [:& radio-button {:value "start"
                     :icon  (get-layout-flex-icon :align-self :start is-col)
                     :title "Align self start"
                     :id     "align-self-start"}]
   [:& radio-button {:value "center"
                     :icon  (get-layout-flex-icon :align-self :center is-col)
                     :title "Align self center"
                     :id    "align-self-center"}]
   [:& radio-button {:value "end"
                     :icon  (get-layout-flex-icon :align-self :end is-col)
                     :title "Align self end"
                     :id    "align-self-end"}]])

(mf/defc layout-item-menu
  {::mf/memo #{:ids :values :type :is-layout-child? :is-grid-parent :is-flex-parent?}
   ::mf/props :obj}
  [{:keys [ids values
           ^boolean is-layout-child?
           ^boolean is-layout-container?
           ^boolean is-grid-parent?
           ^boolean is-flex-parent?
           ^boolean is-flex-layout?
           ^boolean is-grid-layout?]}]

  (let [selection-parents* (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        selection-parents  (mf/deref selection-parents*)

        ^boolean
        is-absolute?       (:layout-item-absolute values)

        ^boolean
        is-col?            (every? ctl/col? selection-parents)

        ^boolean
        is-layout-child?   (and is-layout-child? (not is-absolute?))

        state*             (mf/use-state true)
        open?              (deref state*)

        toggle-content     (mf/use-fn #(swap! state* not))
        has-content?       (or is-layout-child?
                               is-flex-parent?
                               is-grid-parent?
                               is-layout-container?)

        ;; Align self
        align-self         (:layout-item-align-self values)
        h-sizing           (:layout-item-h-sizing values)
        v-sizing           (:layout-item-v-sizing values)

        title
        (cond
          (and is-layout-container?
               is-flex-layout?
               (not is-layout-child?))
          "Flex board"

          (and is-layout-container?
               is-grid-layout?
               (not is-layout-child?))
          "Grid board"

          (and is-layout-container?
               (not is-layout-child?))
          "Layout board"

          is-flex-parent?
          "Flex element"

          is-grid-parent?
          "Grid element"

          :else
          "Layout element")

        on-align-self-change
        (mf/use-fn
         (mf/deps ids align-self)
         (fn [value]
           (if (= align-self value)
             (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
             (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self value})))))

        ;; Margin
        on-margin-type-change
        (mf/use-fn
         (mf/deps ids)
         (fn [type]
           (st/emit! (dwsl/update-layout-child ids {:layout-item-margin-type type}))))

        on-margin-change
        (mf/use-fn
         (mf/deps ids)
         (fn [type prop val]
           (cond
             (and (= type :simple) (= prop :m1))
             (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m1 val :m3 val}}))

             (and (= type :simple) (= prop :m2))
             (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m2 val :m4 val}}))

             :else
             (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {prop val}})))))

        ;; Behaviour
        on-behaviour-h-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout-child ids {:layout-item-h-sizing value}))))

        on-behaviour-v-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout-child ids {:layout-item-v-sizing value}))))

        ;; Size and position
        on-size-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "attr")
                          (keyword))]
             (st/emit! (dwsl/update-layout-child ids {attr value})))))

        on-change-position
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (when (= value :static)
             (st/emit! (dwsl/update-layout-child ids {:layout-item-z-index nil})))
           (st/emit! (dwsl/update-layout-child ids {:layout-item-absolute (= value :absolute)}))))

        ;; Z Index
        on-change-z-index
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout-child ids {:layout-item-z-index value}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:& title-bar {:collapsable  has-content?
                     :collapsed    (not open?)
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
                               :decode-fn keyword
                               :on-change on-change-position
                               :name "layout-style"
                               :wide true}
             [:& radio-button {:value "static"
                               :id :static-position}]
             [:& radio-button {:value "absolute"
                               :id :absolute-position}]]]

           [:div {:class (stl/css :z-index-wrapper)
                  :title "z-index"}

            [:span {:class (stl/css :icon-text)} "Z"]
            [:> numeric-input*
             {:class (stl/css :numeric-input)
              :placeholder "--"
              :on-focus #(dom/select-target %)
              :on-change #(on-change-z-index %)
              :nillable true
              :value (:layout-item-z-index values)}]]])

        [:div {:class (stl/css :row)}
         [:div {:class (stl/css-case
                        :behaviour-menu true
                        :wrap (and ^boolean is-layout-child?
                                   ^boolean is-layout-container?))}
          [:& element-behaviour-horizontal
           {:is-auto is-layout-container?
            :has-fill is-layout-child?
            :value (:layout-item-h-sizing values)
            :on-change on-behaviour-h-change}]
          [:& element-behaviour-vertical
           {:is-auto is-layout-container?
            :has-fill is-layout-child?
            :value (:layout-item-v-sizing values)
            :on-change on-behaviour-v-change}]]]

        (when (and is-layout-child? is-flex-parent?)
          [:div {:class (stl/css :row)}
           [:& align-self-row {:is-col is-col?
                               :value align-self
                               :on-change on-align-self-change}]])

        (when is-layout-child?
          [:div {:class (stl/css :row)}
           [:> margin-section* {:value (:layout-item-margin values)
                                :type (:layout-item-margin-type values)
                                :on-type-change on-margin-type-change
                                :on-change on-margin-change}]])

        (when (or (= h-sizing :fill)
                  (= v-sizing :fill))
          [:div {:class (stl/css :row)}
           [:div {:class (stl/css :advanced-options)}
            (when (= (:layout-item-h-sizing values) :fill)
              [:div {:class (stl/css :horizontal-fill)}
               [:div {:class (stl/css :layout-item-min-w)
                      :title (tr "workspace.options.layout-item.layout-item-min-w")}

                [:span {:class (stl/css :icon-text)} "MIN W"]
                [:> numeric-input*
                 {:class (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :data-attr "layout-item-min-w"
                  :on-focus dom/select-target
                  :on-change on-size-change
                  :value (get values :layout-item-min-w)
                  :nillable true}]]

               [:div {:class (stl/css :layout-item-max-w)
                      :title (tr "workspace.options.layout-item.layout-item-max-w")}
                [:span {:class (stl/css :icon-text)} "MAX W"]
                [:> numeric-input*
                 {:class (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :data-attr "layout-item-max-w"
                  :on-focus dom/select-target
                  :on-change on-size-change
                  :value (get values :layout-item-max-w)
                  :nillable true}]]])

            (when (= v-sizing :fill)
              [:div {:class (stl/css :vertical-fill)}
               [:div {:class (stl/css :layout-item-min-h)
                      :title (tr "workspace.options.layout-item.layout-item-min-h")}

                [:span {:class (stl/css :icon-text)} "MIN H"]
                [:> numeric-input*
                 {:class (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :data-attr "layout-item-min-h"
                  :on-focus dom/select-target
                  :on-change on-size-change
                  :value (get values :layout-item-min-h)
                  :nillable true}]]

               [:div {:class (stl/css :layout-item-max-h)
                      :title (tr "workspace.options.layout-item.layout-item-max-h")}

                [:span {:class (stl/css :icon-text)} "MAX H"]
                [:> numeric-input*
                 {:class (stl/css :numeric-input)
                  :no-validate true
                  :min 0
                  :data-wrap true
                  :placeholder "--"
                  :data-attr "layout-item-max-h"
                  :on-focus dom/select-target
                  :on-change on-size-change
                  :value (get values :layout-item-max-h)
                  :nillable true}]]])]])])]))
