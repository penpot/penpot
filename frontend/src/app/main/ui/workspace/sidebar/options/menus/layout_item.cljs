;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.token :as tk]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [get-layout-flex-icon]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc numeric-input-wrapper*
  {::mf/private true}
  [{:keys [values name applied-tokens align on-detach placeholder] :rest props}]
  (let [tokens (mf/use-ctx muc/active-tokens-by-type)
        input-type (cond
                     (some #{:m2 :m4} [name])
                     :horizontal-margin

                     (some #{:m1 :m3} [name])
                     :vertical-margin

                     (= name :layout-item-max-w)
                     :max-width

                     (= name :layout-item-max-h)
                     :max-height

                     (= name :layout-item-min-w)
                     :min-width

                     (= name :layout-item-min-h)
                     :min-height

                     :else
                     name)

        tokens (mf/with-memo [tokens input-type]
                 (delay
                   (-> (deref tokens)
                       (select-keys (get tk/tokens-by-input input-type))
                       (not-empty))))
        on-detach-attr
        (mf/use-fn
         (mf/deps on-detach name)
         #(on-detach % name))

        props  (mf/spread-props props
                                {:placeholder (or placeholder "--")
                                 :applied-token (get applied-tokens name)
                                 :tokens tokens
                                 :align align
                                 :on-detach on-detach-attr
                                 :value (get values name)})]
    [:> numeric-input* props]))

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
  [{:keys [value on-change on-blur applied-tokens ids]}]
  (let [token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        m1 (:m1 value)
        m2 (:m2 value)
        m3 (:m3 value)
        m4 (:m4 value)

        m1 (when (and (not= value :multiple) (= m1 m3)) m1)
        m2 (when (and (not= value :multiple) (= m2 m4)) m2)

        token-applied-m1 (:m1 applied-tokens)
        token-applied-m2 (:m2 applied-tokens)
        token-applied-m3 (:m3 applied-tokens)
        token-applied-m4 (:m4 applied-tokens)

        token-applied-m1 (if (and (not= applied-tokens :multiple) (= token-applied-m1 token-applied-m3)) token-applied-m1
                             :multiple)

        token-applied-m2 (if (and (not= applied-tokens :multiple) (= token-applied-m2 token-applied-m4)) token-applied-m2
                             :multiple)

        m1-placeholder (if (and (not= value :multiple)
                                (= m1 m3)
                                (= token-applied-m1 token-applied-m3))
                         "--"
                         (tr "settings.multiple"))

        m2-placeholder (if (and (not= value :multiple)
                                (= m2 m4)
                                (= token-applied-m2 token-applied-m4))
                         "--"
                         (tr "settings.multiple"))

        on-focus
        (mf/use-fn
         (mf/deps select-margins)
         (fn [attr event]
           (case attr
             :m1 (select-margins true false true false)
             :m2 (select-margins false true false true))
           (dom/select-target event)))

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token attr]
           (st/emit! (dwta/unapply-token {:token (first token)
                                          :attributes #{attr}
                                          :shape-ids ids}))))

        on-detach-horizontal
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (run! #(on-detach-token token %) [:m2 :m4])))

        on-detach-vertical
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (run! #(on-detach-token token %) [:m1 :m3])))

        on-change'
        (mf/use-fn
         (mf/deps on-change ids)
         (fn [value attr]
           (if (or (string? value) (int? value))
             (on-change :simple attr value)
             (do
               (st/emit!
                (dwta/toggle-token {:token     (first value)
                                    :attrs     (if (= :m1 attr)
                                                 #{:m1 :m3}
                                                 #{:m2 :m4})
                                    :shape-ids ids}))))))

        on-focus-m1
        (mf/use-fn (mf/deps on-focus) #(on-focus :m1))

        on-focus-m2
        (mf/use-fn (mf/deps on-focus) #(on-focus :m2))

        on-m1-change
        (mf/use-fn (mf/deps on-change') #(on-change' % :m1))

        on-m2-change
        (mf/use-fn (mf/deps on-change') #(on-change' % :m2))]

    [:div {:class (stl/css :margin-simple)}
     (if token-numeric-inputs
       [:> numeric-input-wrapper*
        {:on-change on-m1-change
         :on-detach on-detach-vertical
         :class (stl/css :vertical-margin-wrapper)
         :on-blur on-blur
         :on-focus on-focus-m1
         :placeholder m1-placeholder
         :icon i/margin-top-bottom
         :min 0
         :name :m1
         :property "Vertical margin "
         :nillable true
         :applied-tokens {:m1 token-applied-m1}
         :values {:m1 m1}}]

       [:div {:class (stl/css :vertical-margin)
              :title "Vertical margin"}
        [:span {:class (stl/css :icon)}
         deprecated-icon/margin-top-bottom]
        [:> deprecated-input/numeric-input* {:class (stl/css :numeric-input)
                                             :placeholder m1-placeholder
                                             :data-name "m1"
                                             :on-focus on-focus-m1
                                             :on-change on-m1-change
                                             :on-blur on-blur
                                             :nillable true
                                             :value m1}]])

     (if token-numeric-inputs
       [:> numeric-input-wrapper*
        {:on-change on-m2-change
         :on-detach on-detach-horizontal
         :on-blur on-blur
         :on-focus on-focus-m2
         :placeholder m2-placeholder
         :icon i/margin-left-right
         :class (stl/css :horizontal-margin-wrapper)
         :min 0
         :name :m2
         :align :right
         :property "Horizontal margin"
         :nillable true
         :applied-tokens {:m2 token-applied-m2}
         :values {:m2 m2}}]

       [:div {:class (stl/css :horizontal-margin)
              :title "Horizontal margin"}
        [:span {:class (stl/css :icon)}
         deprecated-icon/margin-left-right]
        [:> deprecated-input/numeric-input* {:class (stl/css :numeric-input)
                                             :placeholder m2-placeholder
                                             :data-name "m2"
                                             :on-focus on-focus-m2
                                             :on-change on-m2-change
                                             :on-blur on-blur
                                             :nillable true
                                             :value m2}]])]))

(mf/defc margin-multiple*
  [{:keys [value on-change on-blur applied-tokens ids]}]
  (let [token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        m1     (:m1 value)
        m2     (:m2 value)
        m3     (:m3 value)
        m4     (:m4 value)

        applied-token-to-m1     (:m1 applied-tokens)
        applied-token-to-m2     (:m2 applied-tokens)
        applied-token-to-m3     (:m3 applied-tokens)
        applied-token-to-m4     (:m4 applied-tokens)

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token attr]
           (st/emit! (dwta/unapply-token {:token (first token)
                                          :attributes #{attr}
                                          :shape-ids ids}))))

        on-focus
        (mf/use-fn
         (mf/deps select-margin)
         (fn [attr event]
           (select-margin attr)
           (dom/select-target event)))

        on-focus-m1
        (mf/use-fn (mf/deps on-focus) #(on-focus :m1))

        on-focus-m2
        (mf/use-fn (mf/deps on-focus) #(on-focus :m2))

        on-focus-m3
        (mf/use-fn (mf/deps on-focus) #(on-focus :m1))

        on-focus-m4
        (mf/use-fn (mf/deps on-focus) #(on-focus :m2))

        on-change'
        (mf/use-fn
         (mf/deps on-change ids)
         (fn [value attr]
           (if (or (string? value) (int? value))
             (on-change :multiple attr value)
             (do
               (st/emit!
                (dwta/toggle-token {:token     (first value)
                                    :attrs     #{attr}
                                    :shape-ids ids}))))))


        on-m1-change
        (mf/use-fn (mf/deps on-change') #(on-change' % :m1))

        on-m2-change
        (mf/use-fn (mf/deps on-change') #(on-change' % :m2))

        on-m3-change
        (mf/use-fn (mf/deps on-change') #(on-change' % :m3))

        on-m4-change
        (mf/use-fn (mf/deps on-change') #(on-change' % :m4))]

    [:div {:class (stl/css :margin-multiple)}
     (if token-numeric-inputs
       [:> numeric-input-wrapper*
        {:on-change on-m1-change
         :on-detach on-detach-token
         :on-blur on-blur
         :on-focus on-focus-m1
         :icon i/margin-top
         :class (stl/css :top-margin-wrapper)
         :min 0
         :name :m1
         :property "Top margin"
         :nillable true
         :applied-tokens {:m1 applied-token-to-m1}
         :values {:m1 m1}}]

       [:div {:class (stl/css :top-margin)
              :title "Top margin"}
        [:span {:class (stl/css :icon)}
         deprecated-icon/margin-top]
        [:> deprecated-input/numeric-input* {:class (stl/css :numeric-input)
                                             :placeholder "--"
                                             :data-name "m1"
                                             :on-focus on-focus-m1
                                             :on-change on-m1-change
                                             :on-blur on-blur
                                             :nillable true
                                             :value m1}]])
     (if token-numeric-inputs
       [:> numeric-input-wrapper*
        {:on-change on-m2-change
         :on-detach on-detach-token
         :on-blur on-blur
         :on-focus on-focus-m2
         :icon i/margin-right
         :class (stl/css :right-margin-wrapper)
         :min 0
         :name :m2
         :align :right
         :property "Right margin"
         :nillable true
         :applied-tokens {:m2 applied-token-to-m2}
         :values {:m2 m2}}]

       [:div {:class (stl/css :right-margin)
              :title "Right margin"}
        [:span {:class (stl/css :icon)}
         deprecated-icon/margin-right]
        [:> deprecated-input/numeric-input* {:class (stl/css :numeric-input)
                                             :placeholder "--"
                                             :data-name "m2"
                                             :on-focus on-focus-m2
                                             :on-change on-m2-change
                                             :on-blur on-blur
                                             :nillable true
                                             :value m2}]])

     (if token-numeric-inputs
       [:> numeric-input-wrapper*
        {:on-change on-m3-change
         :on-detach on-detach-token
         :on-blur on-blur
         :on-focus on-focus-m3
         :icon i/margin-bottom
         :class (stl/css :bottom-margin-wrapper)
         :min 0
         :name :m3
         :align :right
         :property "Bottom margin"
         :nillable true
         :applied-tokens {:m3 applied-token-to-m3}
         :values {:m3 m3}}]

       [:div {:class (stl/css :bottom-margin)
              :title "Bottom margin"}
        [:span {:class (stl/css :icon)}
         deprecated-icon/margin-bottom]
        [:> deprecated-input/numeric-input* {:class (stl/css :numeric-input)
                                             :placeholder "--"
                                             :data-name "m3"
                                             :on-focus on-focus-m3
                                             :on-change on-m3-change
                                             :on-blur on-blur
                                             :nillable true
                                             :value m3}]])

     (if token-numeric-inputs
       [:> numeric-input-wrapper*
        {:on-change on-m4-change
         :on-detach on-detach-token
         :on-blur on-blur
         :on-focus on-focus-m4
         :icon i/margin-left
         :class (stl/css :left-margin-wrapper)
         :min 0
         :name :m4
         :property "Left margin"
         :nillable true
         :applied-tokens {:m4 applied-token-to-m4}
         :values {:m4 m4}}]

       [:div {:class (stl/css :left-margin)
              :title "Left margin"}
        [:span {:class (stl/css :icon)}
         deprecated-icon/margin-left]
        [:> deprecated-input/numeric-input* {:class (stl/css :numeric-input)
                                             :placeholder "--"
                                             :data-name "m4"
                                             :on-focus on-focus-m4
                                             :on-change on-m4-change
                                             :on-blur on-blur
                                             :nillable true
                                             :value m4}]])]))

(mf/defc margin-section*
  {::mf/private true
   ::mf/expect-props #{:value :type :on-type-change :on-change :applied-tokens :ids}}
  [{:keys [type on-type-change] :as props}]
  (let [type       (d/nilv type :simple)
        on-blur    (mf/use-fn
                    (mf/deps select-margins)
                    #(select-margins false false false false))
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

     [:> icon-button* {:variant "ghost"
                       :aria-pressed (= type :multiple)
                       :aria-label (tr "workspace.layout-grid.editor.margin.expand")
                       :on-click on-type-change'
                       :icon i/margin}]]))

(mf/defc element-behaviour-horizontal*
  {::mf/private true}
  [{:keys [^boolean is-auto ^boolean has-fill value on-change]}]
  [:div {:class (stl/css-case :horizontal-behaviour true
                              :one-element (and (not has-fill) (not is-auto))
                              :two-element (or has-fill is-auto)
                              :three-element (and has-fill is-auto))}
   [:> radio-buttons* {:selected (d/name value)
                       :on-change on-change
                       :name "flex-behaviour-h"
                       :options (remove nil?
                                        [{:id "behaviour-h-fix"
                                          :icon i/fixed-width
                                          :label (tr "workspace.layout-item.fix-width")
                                          :value "fix"}
                                         (when has-fill
                                           {:id "behaviour-h-fill"
                                            :icon i/fill-content
                                            :label (tr "workspace.layout-item.width-100")
                                            :value "fill"})
                                         (when is-auto
                                           {:id "behaviour-h-auto"
                                            :icon i/hug-content
                                            :label (tr "workspace.layout-item.fit-content-horizontal")
                                            :value "auto"})])}]])

(mf/defc element-behaviour-vertical*
  {::mf/private true}
  [{:keys [^boolean is-auto ^boolean has-fill value on-change]}]
  [:div {:class (stl/css-case :vertical-behaviour true
                              :one-element (and (not has-fill) (not is-auto))
                              :two-element (or has-fill is-auto)
                              :three-element (and has-fill is-auto))}
   [:> radio-buttons* {:selected (d/name value)
                       :on-change on-change
                       :name "flex-behaviour-v"
                       :options (remove nil?
                                        [{:id "behaviour-v-fix"
                                          :icon i/fixed-width
                                          :label (tr "workspace.layout-item.fix-height")
                                          :class (stl/css :rotated)
                                          :value "fix"}
                                         (when has-fill
                                           {:id "behaviour-v-fill"
                                            :icon i/fill-content
                                            :label (tr "workspace.layout-item.height-100")
                                            :class (stl/css :rotated)
                                            :value "fill"})
                                         (when is-auto
                                           {:id "behaviour-v-auto"
                                            :icon i/hug-content
                                            :label (tr "workspace.layout-item.fit-content-vertical")
                                            :class (stl/css :rotated)
                                            :value "auto"})])}]])

(mf/defc align-self-row*
  [{:keys [^boolean is-col value on-change]}]
  [:> radio-buttons* {:selected (d/name value)
                      :name "flex-align-self"
                      :on-change on-change
                      :allow-empty true
                      :options [{:id    "align-self-start"
                                 :icon  (get-layout-flex-icon :align-self :start is-col)
                                 :label "Align self start"
                                 :value "start"}
                                {:id    "align-self-center"
                                 :icon  (get-layout-flex-icon :align-self :center is-col)
                                 :label "Align self center"
                                 :value "center"}
                                {:id    "align-self-end"
                                 :icon  (get-layout-flex-icon :align-self :end is-col)
                                 :label "Align self end"
                                 :value "end"}]}])

(def ^:private schema:layout-item-props-schema
  [:map
   [:layout-item-margin
    {:optional true}
    [:map
     [:m1 {:optional true} [:or :float :int]]
     [:m2 {:optional true} [:or :float :int]]
     [:m3 {:optional true} [:or :float :int]]
     [:m4 {:optional true} [:or :float :int]]]]

   [:layout-item-margin-type {:optional true} :keyword]

   [:layout-item-h-sizing {:optional true} :keyword]
   [:layout-item-v-sizing {:optional true} :keyword]

   [:layout-item-min-w {:optional true} [:or :float :int]]
   [:layout-item-max-w {:optional true} [:or :float :int]]
   [:layout-item-min-h {:optional true} [:or :float :int]]
   [:layout-item-max-h {:optional true} [:or :float :int]]])

(def ^:private schema:layout-size-constraints
  [:map
   [:values schema:layout-item-props-schema]
   [:applied-tokens [:map-of :keyword :string]]
   [:ids [::sm/vec ::sm/uuid]]
   [:v-sizing {:optional true} [:maybe [:= :fill]]]])

(mf/defc layout-size-constraints*
  {::mf/private true
   ::mf/schema (sm/schema schema:layout-size-constraints)}
  [{:keys [values v-sizing ids applied-tokens] :as props}]
  (let [token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        min-w (get values :layout-item-min-w)

        max-w (get values :layout-item-max-w)

        min-h (get values :layout-item-min-h)

        max-h (get values :layout-item-max-h)

        applied-token-to-min-w (get applied-tokens :layout-item-min-w)

        applied-token-to-max-w (get applied-tokens :layout-item-max-w)

        applied-token-to-min-h (get applied-tokens :layout-item-min-h)

        applied-token-to-max-h (get applied-tokens :layout-item-max-h)

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token attr]
           (st/emit! (dwta/unapply-token {:token (first token)
                                          :attributes #{attr}
                                          :shape-ids ids}))))

        on-size-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (if (or (string? value) (int? value))
             (st/emit! (dwsl/update-layout-child ids {attr value}))
             (do
               (st/emit!
                (dwta/toggle-token {:token     (first value)
                                    :attrs     #{attr}
                                    :shape-ids ids}))))))

        on-layout-item-min-w-change
        (mf/use-fn (mf/deps on-size-change) #(on-size-change % :layout-item-min-w))

        on-layout-item-max-w-change
        (mf/use-fn (mf/deps on-size-change) #(on-size-change % :layout-item-max-w))

        on-layout-item-min-h-change
        (mf/use-fn (mf/deps on-size-change) #(on-size-change % :layout-item-min-h))

        on-layout-item-max-h-change
        (mf/use-fn (mf/deps on-size-change) #(on-size-change % :layout-item-max-h))]

    [:div {:class (stl/css :advanced-options)}
     (when (= (:layout-item-h-sizing values) :fill)
       [:div {:class (stl/css :horizontal-fill)}
        (if token-numeric-inputs
          [:> numeric-input-wrapper*
           {:on-change on-layout-item-min-w-change
            :on-detach on-detach-token
            :class (stl/css :min-w-wrapper)
            :min 0
            :name :layout-item-min-w
            :property (tr "workspace.options.layout-item.layout-item-min-w")
            :text-icon "MIN W"
            :nillable true
            :applied-tokens {:layout-item-min-w applied-token-to-min-w}
            :tooltip-class (stl/css :tooltip-wrapper)
            :values {:layout-item-min-w min-w}}]

          [:div {:class (stl/css :layout-item-min-w)
                 :title (tr "workspace.options.layout-item.layout-item-min-w")}

           [:span {:class (stl/css :icon-text)} "MIN W"]
           [:> deprecated-input/numeric-input*
            {:class (stl/css :numeric-input)
             :no-validate true
             :min 0
             :data-wrap true
             :placeholder "--"
             :data-attr "layout-item-min-w"
             :on-focus dom/select-target
             :on-change on-layout-item-min-w-change
             :value (get values :layout-item-min-w)
             :nillable true}]])

        (if token-numeric-inputs
          [:> numeric-input-wrapper*
           {:on-change on-layout-item-max-w-change
            :on-detach on-detach-token
            :text-icon "MAX W"
            :class (stl/css :max-w-wrapper)
            :min 0
            :name :layout-item-max-w
            :align :right
            :property (tr "workspace.options.layout-item.layout-item-max-w")
            :nillable true
            :tooltip-class (stl/css :tooltip-wrapper)
            :applied-tokens {:layout-item-max-w applied-token-to-max-w}
            :values {:layout-item-max-w max-w}}]

          [:div {:class (stl/css :layout-item-max-w)
                 :title (tr "workspace.options.layout-item.layout-item-max-w")}
           [:span {:class (stl/css :icon-text)} "MAX W"]
           [:> deprecated-input/numeric-input*
            {:class (stl/css :numeric-input)
             :no-validate true
             :min 0
             :data-wrap true
             :placeholder "--"
             :data-attr "layout-item-max-w"
             :on-focus dom/select-target
             :on-change on-layout-item-max-w-change
             :value (get values :layout-item-max-w)
             :nillable true}]])])

     (when (= v-sizing :fill)
       [:div {:class (stl/css :vertical-fill)}
        (if token-numeric-inputs
          [:> numeric-input-wrapper*
           {:on-change on-layout-item-min-h-change
            :on-detach on-detach-token
            :text-icon "MIN H"
            :class (stl/css :min-h-wrapper)
            :min 0
            :name :layout-item-min-h
            :property (tr "workspace.options.layout-item.layout-item-min-h")
            :nillable true
            :tooltip-class (stl/css :tooltip-wrapper)
            :applied-tokens {:layout-item-min-h applied-token-to-min-h}
            :values {:layout-item-min-h min-h}}]

          [:div {:class (stl/css :layout-item-min-h)
                 :title (tr "workspace.options.layout-item.layout-item-min-h")}
           [:span {:class (stl/css :icon-text)} "MIN H"]
           [:> deprecated-input/numeric-input*
            {:class (stl/css :numeric-input)
             :no-validate true
             :min 0
             :data-wrap true
             :placeholder "--"
             :data-attr "layout-item-min-h"
             :on-focus dom/select-target
             :on-change on-layout-item-min-h-change
             :value (get values :layout-item-min-h)
             :nillable true}]])

        (if token-numeric-inputs
          [:> numeric-input-wrapper*
           {:on-change on-layout-item-max-h-change
            :on-detach on-detach-token
            :class (stl/css :max-h-wrapper)
            :min 0
            :text-icon "MAX H"
            :name :layout-item-max-h
            :align :right
            :property (tr "workspace.options.layout-item.layout-item-max-h")
            :nillable true
            :tooltip-class (stl/css :tooltip-wrapper)
            :applied-tokens {:layout-item-max-h applied-token-to-max-h}
            :values {:layout-item-max-h max-h}}]

          [:div {:class (stl/css :layout-item-max-h)
                 :title (tr "workspace.options.layout-item.layout-item-max-h")}

           [:span {:class (stl/css :icon-text)} "MAX H"]
           [:> deprecated-input/numeric-input*
            {:class (stl/css :numeric-input)
             :no-validate true
             :min 0
             :data-wrap true
             :placeholder "--"
             :data-attr "layout-item-max-h"
             :on-focus dom/select-target
             :on-change on-layout-item-max-h-change
             :value (get values :layout-item-max-h)
             :nillable true}]])])]))

(mf/defc layout-item-menu
  {::mf/memo #{:ids :values :type :is-layout-child? :is-grid-parent :is-flex-parent? :is-grid-layout? :is-flex-layout? :applied-tokens}
   ::mf/props :obj}
  [{:keys [ids values
           ^boolean is-layout-child?
           ^boolean is-layout-container?
           ^boolean is-grid-parent?
           ^boolean is-flex-parent?
           ^boolean is-flex-layout?
           ^boolean is-grid-layout?
           applied-tokens]}]

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
           (let [value (keyword value)]
             (if (= align-self value)
               (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
               (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self value}))))))

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
           (st/emit! (dwsl/update-layout-child ids {:layout-item-h-sizing (keyword value)}))))

        on-behaviour-v-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dwsl/update-layout-child ids {:layout-item-v-sizing (keyword value)}))))

        ;; Position
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

    [:section {:class (stl/css :element-set)
               :aria-label "layout item menu"}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  has-content?
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :title        title
                      :class        (stl/css-case :title-spacing-layout-element true
                                                  :title-spacing-empty (not has-content?))}]]
     (when open?
       [:div {:class (stl/css :flex-element-menu)}
        (when (or is-layout-child? is-absolute?)
          [:div {:class (stl/css :position-row)}
           [:> radio-buttons* {:class (stl/css :position-options)
                               :selected (if is-absolute? "absolute" "static")
                               :on-change on-change-position
                               :name "layout-style"
                               :extended true
                               :options [{:id "static-position"
                                          :label "Static"
                                          :value "static"}
                                         {:id "absolute-position"
                                          :label "Absolute"
                                          :value "absolute"}]}]

           [:div {:class (stl/css :z-index-wrapper)
                  :title "z-index"}

            [:span {:class (stl/css :icon-text)} "Z"]
            [:> deprecated-input/numeric-input*
             {:class (stl/css :numeric-input)
              :placeholder "--"
              :on-focus #(dom/select-target %)
              :on-change #(on-change-z-index %)
              :nillable true
              :value (:layout-item-z-index values)}]]])

        [:div {:class (stl/css :behavior-row)}
         [:div {:class (stl/css-case
                        :behaviour-menu true
                        :wrap (and ^boolean is-layout-child?
                                   ^boolean is-layout-container?))}
          [:> element-behaviour-horizontal* {:is-auto is-layout-container?
                                             :has-fill is-layout-child?
                                             :value (:layout-item-h-sizing values)
                                             :on-change on-behaviour-h-change}]
          [:> element-behaviour-vertical* {:is-auto is-layout-container?
                                           :has-fill is-layout-child?
                                           :value (:layout-item-v-sizing values)
                                           :on-change on-behaviour-v-change}]]]

        (when (and is-layout-child? is-flex-parent?)
          [:div {:class (stl/css :align-row)}
           [:> align-self-row* {:is-col is-col?
                                :value align-self
                                :on-change on-align-self-change}]])

        (when is-layout-child?
          [:> margin-section* {:value (:layout-item-margin values)
                               :type (:layout-item-margin-type values)
                               :on-type-change on-margin-type-change
                               :applied-tokens applied-tokens
                               :ids ids
                               :on-change on-margin-change}])

        (when (or (= h-sizing :fill)
                  (= v-sizing :fill))
          [:> layout-size-constraints* {:ids ids
                                        :values values
                                        :applied-tokens applied-tokens
                                        :v-sizing v-sizing}])])]))
