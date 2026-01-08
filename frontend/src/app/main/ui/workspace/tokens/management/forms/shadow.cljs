;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.forms :as forms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [app.main.ui.workspace.tokens.management.forms.validators :refer [check-self-reference
                                                                     default-validate-token]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- check-shadow-token-self-reference
  "Check token when any of the attributes in a shadow's value have a self-reference."
  [token]
  (let [token-name (:name token)
        shadow-values (:value token)]
    (some (fn [[shadow-idx shadow-map]]
            (some (fn [[k v]]
                    (when-let [err (check-self-reference token-name v)]
                      (assoc err :shadow-key k :shadow-index shadow-idx)))
                  shadow-map))
          (d/enumerate shadow-values))))

(defn- check-empty-shadow-token [token]
  (when (or (empty? (:value token))
            (some (fn [shadow] (not-every? #(contains? shadow %) [:offset-x :offset-y :blur :spread :color]))
                  (:value token)))
    (wte/get-error-code :error.token/empty-input)))

(defn- validate-shadow-token
  [{:keys [token-value] :as params}]
  (cond
    ;; Entering form without a value - show no error just resolve nil
    (nil? token-value) (rx/of nil)
    ;; Validate refrence string
    (cto/shadow-composite-token-reference? token-value) (default-validate-token params)
    ;; Validate composite token
    :else
    (let [params (-> params
                     (update :token-value (fn [value]
                                            (->> (or value [])
                                                 (mapv (fn [shadow]
                                                         (d/update-when shadow :inset #(cond
                                                                                         (boolean? %) %
                                                                                         (= "true" %) true
                                                                                         :else false)))))))
                     (assoc :validators [check-empty-shadow-token
                                         check-shadow-token-self-reference]))]

      (default-validate-token params))))

(def ^:private default-token-shadow
  {:offset-x "4"
   :offset-y "4"
   :blur "4"
   :spread "0"})

(defn get-subtoken
  [token index prop value-subfield]
  (let [value (get-in token [:value value-subfield index prop])]
    (d/without-nils
     {:type  (if (= prop :color) :color :dimensions)
      :value value})))

(mf/defc shadow-formset*
  [{:keys [index token tokens remove-shadow-block show-button value-subfield] :as props}]
  (let [inset-token (get-subtoken token index :inset value-subfield)
        inset-token (hooks/use-equal-memo inset-token)

        color-token (get-subtoken token index :color value-subfield)
        color-token (hooks/use-equal-memo color-token)

        offset-x-token (get-subtoken token index :offset-x value-subfield)
        offset-x-token (hooks/use-equal-memo offset-x-token)

        offset-y-token (get-subtoken token index :offset-y value-subfield)
        offset-y-token (hooks/use-equal-memo offset-y-token)

        blur-token (get-subtoken token index :blur value-subfield)
        blur-token (hooks/use-equal-memo blur-token)

        spread-token (get-subtoken token index :spread value-subfield)
        spread-token (hooks/use-equal-memo spread-token)

        on-button-click
        (mf/use-fn
         (mf/deps index)
         (fn [event]
           (remove-shadow-block index event)))]

    [:div {:class (stl/css :shadow-block)
           :data-testid (str "shadow-input-fields-" index)}
     [:div {:class (stl/css :select-wrapper)}
      [:> token.controls/select-indexed* {:options [{:id "drop" :label "drop shadow" :icon i/drop-shadow}
                                                    {:id "inner" :label "inner shadow" :icon i/inner-shadow}]
                                          :aria-label (tr "workspace.tokens.shadow-inset")
                                          :token inset-token
                                          :tokens tokens
                                          :index index
                                          :indexed-type value-subfield
                                          :name :inset}]
      (when show-button
        [:> icon-button* {:variant "ghost"
                          :type "button"
                          :aria-label (tr "workspace.tokens.shadow-remove-shadow")
                          :on-click on-button-click
                          :icon i/remove}])]
     [:div {:class (stl/css :inputs-wrapper)}
      [:div {:class (stl/css :input-row)}
       [:> token.controls/indexed-color-input*
        {:placeholder (tr "workspace.tokens.token-value-enter")
         :aria-label (tr "workspace.tokens.color")
         :name :color
         :token color-token
         :value-subfield value-subfield
         :index index
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> token.controls/input-indexed*
        {:aria-label (tr "workspace.tokens.shadow-x")
         :icon i/character-x
         :placeholder (tr "workspace.tokens.shadow-x")
         :name :offset-x
         :token offset-x-token
         :index index
         :value-subfield value-subfield
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> token.controls/input-indexed*
        {:aria-label (tr "workspace.tokens.shadow-y")
         :icon i/character-y
         :placeholder (tr "workspace.tokens.shadow-y")
         :name :offset-y
         :token offset-y-token
         :index index
         :value-subfield value-subfield
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> token.controls/input-indexed*
        {:aria-label (tr "workspace.tokens.shadow-blur")
         :placeholder (tr "workspace.tokens.shadow-blur")
         :name :blur
         :slot-start (mf/html [:span {:class (stl/css :visible-label)}
                               (str (tr "workspace.tokens.shadow-blur") ":")])
         :token blur-token
         :index index
         :value-subfield value-subfield
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> token.controls/input-indexed*
        {:aria-label (tr "workspace.tokens.shadow-spread")
         :placeholder (tr "workspace.tokens.shadow-spread")
         :name :spread
         :slot-start (mf/html [:span {:class (stl/css :visible-label)}
                               (str (tr "workspace.tokens.shadow-spread") ":")])
         :token spread-token
         :value-subfield value-subfield
         :index index
         :tokens tokens}]]]]))

(mf/defc composite-form*
  [{:keys [token tokens remove-shadow-block value-subfield] :as props}]
  (let [form
        (mf/use-ctx forms/context)

        length
        (-> form deref :data :value value-subfield count)]

    (for [index (range length)]
      [:> shadow-formset* {:key index
                           :index index
                           :token token
                           :tokens tokens
                           :value-subfield value-subfield
                           :remove-shadow-block remove-shadow-block
                           :show-button (> length 1)}])))

(mf/defc reference-form*
  [{:keys [token tokens] :as props}]
  [:div {:class (stl/css :input-row-reference)}
   [:> token.controls/input-composite*
    {:placeholder (tr "workspace.tokens.reference-composite-shadow")
     :aria-label (tr "labels.reference")
     :icon i/drop-shadow
     :name :reference
     :token token
     :tokens tokens}]])

(mf/defc tabs-wrapper*
  [{:keys [token tokens tab handle-toggle value-subfield] :rest props}]
  (let [form (mf/use-ctx forms/context)
        on-add-shadow-block
        (mf/use-fn
         (mf/deps value-subfield)
         (fn []
           (swap! form  update-in [:data :value value-subfield] conj default-token-shadow)))

        remove-shadow-block
        (mf/use-fn
         (mf/deps value-subfield)
         (fn [index event]
           (dom/prevent-default event)
           (swap! form update-in [:data :value value-subfield] #(d/remove-at-index % index))))]

    [:*
     [:div {:class (stl/css :title-bar)}
      [:div {:class (stl/css :title)} (tr "labels.shadow")]
      [:> icon-button* {:variant "ghost"
                        :type "button"
                        :aria-label (tr "workspace.tokens.shadow-add-shadow")
                        :on-click on-add-shadow-block
                        :icon i/add}]
      [:> radio-buttons* {:selected (d/name tab)
                          :on-change handle-toggle
                          :name "reference-composite-tab"
                          :options [{:id "composite-opt"
                                     :icon i/layers
                                     :label (tr "workspace.tokens.individual-tokens")
                                     :value "composite"}
                                    {:id "reference-opt"
                                     :icon i/tokens
                                     :label (tr "workspace.tokens.use-reference")
                                     :value "reference"}]}]]

     (if (= tab :composite)
       [:> composite-form* {:token token
                            :tokens tokens
                            :remove-shadow-block remove-shadow-block
                            :value-subfield value-subfield}]

       [:> reference-form* {:token token
                            :tokens tokens}])]))

(defn- make-schema
  [tokens-tree active-tab]
  (sm/schema
   [:and
    [:map
     [:name
      [:and
       [:string {:min 1 :max 255
                 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
       (sm/update-properties cto/token-name-ref assoc
                             :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
       [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
        #(not (cft/token-name-path-exists? % tokens-tree))]]]

     [:value
      [:map
       [:shadow {:optional true}
        [:vector
         [:map
          [:offset-x {:optional true} [:maybe :string]]
          [:offset-y {:optional true} [:maybe :string]]
          [:blur {:optional true}
           [:and
            [:maybe :string]
            [:fn {:error/fn #(tr "workspace.tokens.shadow-token-blur-value-error")}
             (fn [blur]
               (let [n (d/parse-double blur)]
                 (or (nil? n) (not (< n 0)))))]]]
          [:spread {:optional true}
           [:and
            [:maybe :string]
            [:fn {:error/fn #(tr "workspace.tokens.shadow-token-spread-value-error")}
             (fn [spread]
               (let [n (d/parse-double spread)]
                 (or (nil? n) (not (< n 0)))))]]]
          [:color {:optional true} [:maybe :string]]
          [:color-result {:optional true} ::sm/any]
          [:inset {:optional true} [:maybe :boolean]]]]]
       (if (= active-tab :reference)
         [:reference {:optional false} ::sm/text]
         [:reference {:optional true} [:maybe :string]])]]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]

    [:fn {:error/field [:value :reference]
          :error/fn #(tr "workspace.tokens.self-reference")}
     (fn [{:keys [name value]}]
       (let [reference (get value :reference)]
         (if (and reference name)
           (not (cto/token-value-self-reference? name reference))
           true)))]

    [:fn {:error/fn (fn [_] "Must be a valid shadow or reference")
          :error/field :value}
     (fn [{:keys [value]}]
       (let [reference  (get value :reference)
             ref-valid? (and reference (not (str/blank? reference)))

             shadows (get value :shadow)
             ;; To be a valid shadow it must contain one on each valid values
             valid-composite-shadow?
             (and (seq shadows)
                  (every?
                   (fn [{:keys [offset-x offset-y blur spread color]}]
                     (and (not (str/blank? offset-x))
                          (not (str/blank? offset-y))
                          (not (str/blank? blur))
                          (not (str/blank? spread))
                          (not (str/blank? color))))
                   shadows))]

         (or ref-valid? valid-composite-shadow?)))]]))

(defn- make-default-value
  [value]
  (cond
    (string? value)
    {:reference value
     :shadow   []}

    (vector? value)
    {:reference nil
     :shadow   value}

    :else
    {:reference nil
     :shadow   [default-token-shadow]}))

(mf/defc form*
  [{:keys [token
           token-type] :as props}]
  (let [token
        (mf/with-memo [token]
          (or token
              (if-let [value (get token :value)]
                {:type token-type
                 :value (make-default-value value)}

                {:type token-type
                 :value {:reference nil
                         :shadow   [default-token-shadow]}})))
        initial
        (mf/with-memo [token]
          (let [raw-value (:value token)
                value (make-default-value raw-value)]

            {:name        (:name token "")
             :description (:description token "")
             :value       value}))

        props (mf/spread-props props {:token token
                                      :token-type token-type
                                      :initial initial
                                      :make-schema make-schema
                                      :type :indexed
                                      :value-subfield :shadow
                                      :input-component tabs-wrapper*
                                      :validator validate-shadow-token})]
    [:> generic/form* props]))
