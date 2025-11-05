;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]

   [app.common.types.token :as cto]
   [app.main.data.style-dictionary :as sd]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token*]]
   [app.main.ui.workspace.tokens.management.create.shared.color-picker :refer [color-picker*]]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc inset-type-select*
  {::mf/private true}
  [{:keys [default-value shadow-idx on-change]}]
  (let [selected* (mf/use-state (or (str default-value) "false"))
        selected (deref selected*)

        on-change
        (mf/use-fn
         (mf/deps on-change selected shadow-idx)
         (fn [value e]
           #_(obj/set! e "tokenValue" (if (= "true" value) true false))
           (prn value)
          ;;  (on-change e)
           #_(reset! selected* value)))
        options 
        (mf/with-memo []
          [{:id "drop-shadow" :label "drop-shadow" :icon i/drop-shadow}
           {:id "inner-shadow" :label "inner-shadow" :icon i/inner-shadow}])]
    
    [:div {:class (stl/css :input-row)}
     [:> select* {:default-selected selected
                  :variant "ghost"
                  :options options
                  :on-change on-change}]]))

(def ^:private shadow-inputs
  #(d/ordered-map
    :inset
    {:label (tr "workspace.tokens.shadow-inset")
     :placeholder (tr "workspace.tokens.shadow-inset")}
    :color
    {:label (tr "workspace.tokens.shadow-color")
     :placeholder (tr "workspace.tokens.shadow-color")}
    :offsetX
    {:label (tr "workspace.tokens.shadow-x")
     :placeholder (tr "workspace.tokens.shadow-x")}
    :offsetY
    {:label (tr "workspace.tokens.shadow-y")
     :placeholder (tr "workspace.tokens.shadow-y")}
    :blur
    {:label (tr "workspace.tokens.shadow-blur")
     :placeholder (tr "workspace.tokens.shadow-blur")}
    :spread
    {:label (tr "workspace.tokens.shadow-spread")
     :placeholder (tr "workspace.tokens.shadow-spread")}))

(def ^:private input-icon
  {:offsetX i/character-x
   :offsetY i/character-y})

(mf/defc shadow-color-picker-wrapper*
  "Wrapper for color-picker* that passes shadow color state from parent.
   Similar to color-form* but receives color state from shadow-value-inputs*."
  {::mf/private true}
  [{:keys [placeholder label default-value input-ref on-update-value on-external-update-value token-resolve-result shadow-color]}]
  (let [;; Use the color state passed from parent (shadow-value-inputs*)
        resolved-color (get token-resolve-result :resolved-value)
        color (or shadow-color resolved-color default-value "")

        custom-input-token-value-props
        (mf/use-memo
         (mf/deps color)
         (fn []
           {:color color}))]

    [:> color-picker*
     {:placeholder placeholder
      :label label
      :default-value default-value
      :input-ref input-ref
      :on-update-value on-update-value
      :on-external-update-value on-external-update-value
      :custom-input-token-value-props custom-input-token-value-props
      :token-resolve-result token-resolve-result}]))

(mf/defc shadow-input*
  {::mf/private true}
  [{:keys [default-value label placeholder shadow-idx input-type on-update-value on-external-update-value token-resolve-result errors-by-key shadow-color]}]
  (let [color-input-ref (mf/use-ref)
        on-change
        (mf/use-fn
         (mf/deps shadow-idx input-type on-update-value)
         (fn [e]
           (-> (obj/set! e "tokenTypeAtIndex" [shadow-idx input-type])
               (on-update-value))))

        on-external-update-value'
        (mf/use-fn
         (mf/deps shadow-idx input-type on-external-update-value)
         (fn [v]
           (on-external-update-value [shadow-idx input-type] v)))

        resolved (get-in token-resolve-result [:resolved-value shadow-idx input-type])

        errors (get errors-by-key input-type)

        should-show? (or (some? resolved) (seq errors))

        token-prop (when should-show?
                     (d/without-nils
                      {:resolved-value resolved
                       :errors errors}))]
    (case input-type
      :inset
      [:> inset-type-select*
       {:default-value default-value
        :shadow-idx shadow-idx
        :label label
        :on-change on-change}]
      
      :color
      [:> shadow-color-picker-wrapper*
       {:placeholder placeholder
        :aria-label label
        :default-value default-value
        :input-ref color-input-ref
        :on-update-value on-change
        :on-external-update-value on-external-update-value'
        :token-resolve-result token-prop
        :shadow-color (or shadow-color nil)
        :data-testid (str "shadow-color-input-" shadow-idx)}]
      
      [:div {:class (stl/css :input-row)
             :data-testid (str "shadow-" (name input-type) "-input-" shadow-idx)}
       [:> input-token*
        {:aria-label label
         :icon (get input-icon input-type)
         :placeholder placeholder
         :default-value default-value
         :on-change on-change
         :slot-start (cond (= input-type :blur)
                       (mf/html [:span {:class (stl/css :shadow-prop-label)} "Blur"])
                       (= input-type :spread)
                       (mf/html [:span {:class (stl/css :shadow-prop-label)} "Spread"]))
         :token-resolve-result token-prop}]])))

(mf/defc shadow-input-fields*
  {::mf/private true}
  [{:keys [shadow shadow-idx on-remove-shadow on-add-shadow is-remove-disabled on-update-value token-resolve-result errors-by-key on-external-update-value shadow-color] :as props}]
  (let [on-remove-shadow
        (mf/use-fn
         (mf/deps shadow-idx on-remove-shadow)
         #(on-remove-shadow shadow-idx))]
    [:div {:data-testid (str "shadow-input-fields-" shadow-idx)
           :class (stl/css :shadow-input-fields)}
     [:> icon-button* {:icon i/add
                       :type "button"
                       :on-click on-add-shadow
                       :data-testid (str "shadow-add-button-" shadow-idx)
                       :aria-label (tr "workspace.tokens.shadow-add-shadow")}]
     [:> icon-button* {:variant "ghost"
                       :type "button"
                       :icon i/remove
                       :on-click on-remove-shadow
                       :disabled is-remove-disabled
                       :data-testid (str "shadow-remove-button-" shadow-idx)
                       :aria-label (tr "workspace.tokens.shadow-remove-shadow")}]
     (for [[input-type {:keys [label placeholder]}] (shadow-inputs)]
       [:> shadow-input*
        {:key (str input-type shadow-idx)
         :input-type input-type
         :label label
         :placeholder placeholder
         :shadow-idx shadow-idx
         :default-value (get shadow input-type)
         :on-update-value on-update-value
         :token-resolve-result token-resolve-result
         :errors-by-key errors-by-key
         :on-external-update-value on-external-update-value
         :shadow-color shadow-color}])]))

(mf/defc shadow-value-inputs*
  [{:keys [default-value on-update-value token-resolve-result update-composite-value] :as props}]
  (let [shadows* (mf/use-state (or default-value [{}]))
        shadows (deref shadows*)
        shadows-count (count shadows)
        composite-token? (not (cto/typography-composite-token-reference? (:value token-resolve-result)))

        ;; Maintain a map of color states for each shadow to prevent reset on add/remove
        shadow-colors* (mf/use-state {})
        shadow-colors (deref shadow-colors*)

        ;; Define on-external-update-value here where we have access to on-update-value
        on-external-update-value
        (mf/use-fn
         (mf/deps on-update-value shadow-colors*)
         (fn [token-type-at-index value]
           (let [[idx token-type] token-type-at-index
                 e (js-obj)]
             ;; Update shadow color state if this is a color update
             (when (= token-type :color)
               (swap! shadow-colors* assoc idx value))
             (obj/set! e "tokenTypeAtIndex" token-type-at-index)
             (obj/set! e "target" #js {:value value})
             (on-update-value e))))

        on-add-shadow
        (mf/use-fn
         (mf/deps shadows update-composite-value)
         (fn []
           (update-composite-value
            (fn [state]
              (let [new-state (update state :composite (fnil conj []) {})]
                (reset! shadows* (:composite new-state))
                new-state)))))

        on-remove-shadow
        (mf/use-fn
         (mf/deps shadows update-composite-value)
         (fn [idx]
           (update-composite-value
            (fn [state]
              (let [new-state (update state :composite d/remove-at-index idx)]
                (reset! shadows* (:composite new-state))
                new-state)))))]

    (mf/use-effect
     (mf/deps shadows)
     (fn []
       (doseq [[idx shadow] (d/enumerate shadows)]
         (when-not (contains? shadow-colors idx)
           (let [resolved-color (get-in token-resolve-result [:resolved-value idx :color])
                 initial-color (or resolved-color (get shadow :color) "")]
             (swap! shadow-colors* assoc idx initial-color))))))

    [:div {:class (stl/css :nested-input-row)}
     (for [[shadow-idx shadow] (d/enumerate shadows)
           :let [is-remove-disabled (= shadows-count 1)
                 key (str shadows-count shadow-idx)
                 errors-by-key (when composite-token?
                                 (sd/collect-shadow-errors token-resolve-result shadow-idx))]]
       [:div {:key key
              :class (stl/css :nested-input-row)}
        [:> shadow-input-fields*
         {:is-remove-disabled is-remove-disabled
          :shadow-idx shadow-idx
          :on-add-shadow on-add-shadow
          :on-remove-shadow on-remove-shadow
          :shadow shadow
          :on-update-value on-update-value
          :token-resolve-result token-resolve-result
          :errors-by-key errors-by-key
          :on-external-update-value on-external-update-value
          :shadow-color (get shadow-colors shadow-idx "")}]])]))
