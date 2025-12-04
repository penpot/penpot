;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.typography
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [app.main.ui.workspace.tokens.management.forms.validators :refer [check-coll-self-reference check-self-reference default-validate-token]]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; VALIDATORS

(defn- check-typography-token-self-reference
  "Check token when any of the attributes in token value have a self-reference."
  [token]
  (let [token-name (:name token)
        token-values (:value token)]
    (some (fn [[k v]]
            (when-let [err (case k
                             :font-family (check-coll-self-reference token-name v)
                             (check-self-reference token-name v))]
              (assoc err :typography-key k)))
          token-values)))

(defn- check-empty-typography-token [token]
  (when (empty? (:value token))
    (wte/get-error-code :error.token/empty-input)))

(defn- validate-typography-token
  [{:keys [token-value] :as props}]
  (cond
    ;; Entering form without a value - show no error just resolve nil
    (nil? token-value) (rx/of nil)
    ;; Validate refrence string
    (cto/typography-composite-token-reference? token-value) (default-validate-token props)
    ;; Validate composite token
    :else
    (-> props
        (update :token-value
                (fn [v]
                  (-> (or v {})
                      (d/update-when :font-family #(if (string? %) (cto/split-font-family %) %)))))
        (assoc :validators [check-empty-typography-token
                            check-typography-token-self-reference])
        (default-validate-token))))

;; COMPONENTS

(mf/defc composite-form*
  [{:keys [token tokens] :as props}]
  (let [letter-spacing-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :letter-spacing
             :value (cto/join-font-family (get value :letter-spacing))}
            {:type :letter-spacing}))

        font-family-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :font-family
             :value (get value :font-family)}
            {:type :font-family}))

        font-size-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :font-size
             :value (get value :font-size)}
            {:type :font-size}))

        font-weight-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :font-weight
             :value (get value :font-weight)}
            {:type :font-weight}))

        line-height-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :number
             :value (get value :line-height)}
            {:type :number}))

        text-case-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :text-case
             :value (get value :text-case)}
            {:type :text-case}))

        text-decoration-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :text-decoration
             :value (get value :text-decoration)}
            {:type :text-decoration}))]

    [:*
     [:div {:class (stl/css :input-row)}
      [:> token.controls/composite-fonts-combobox*
       {:icon i/text-font-family
        :placeholder (tr "workspace.tokens.token-font-family-value-enter")
        :aria-label  (tr "workspace.tokens.token-font-family-value")
        :name :font-family
        :token font-family-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> token.controls/input-composite*
       {:aria-label "Font Size"
        :icon i/text-font-size
        :placeholder (tr "workspace.tokens.font-size-value-enter")
        :name :font-size
        :token font-size-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> token.controls/input-composite*
       {:aria-label "Font Weight"
        :icon i/text-font-weight
        :placeholder (tr "workspace.tokens.font-weight-value-enter")
        :name :font-weight
        :token font-weight-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> token.controls/input-composite*
       {:aria-label "Line Height"
        :icon i/text-lineheight
        :placeholder (tr "workspace.tokens.line-height-value-enter")
        :name :line-height
        :token line-height-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> token.controls/input-composite*
       {:aria-label "Letter Spacing"
        :icon i/text-letterspacing
        :placeholder (tr "workspace.tokens.letter-spacing-value-enter-composite")
        :name :letter-spacing
        :token letter-spacing-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> token.controls/input-composite*
       {:aria-label "Text Case"
        :icon i/text-mixed
        :placeholder (tr "workspace.tokens.text-case-value-enter")
        :name :text-case
        :token text-case-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> token.controls/input-composite*
       {:aria-label "Text Decoration"
        :icon i/text-underlined
        :placeholder (tr "workspace.tokens.text-decoration-value-enter")
        :name :text-decoration
        :token text-decoration-sub-token
        :tokens tokens}]]]))

(mf/defc reference-form*
  [{:keys [token tokens] :as props}]
  [:div {:class (stl/css :input-row)}
   [:> token.controls/input-composite*
    {:placeholder (tr "workspace.tokens.reference-composite")
     :aria-label (tr "labels.reference")
     :icon i/text-typography
     :name :reference
     :token token
     :tokens tokens}]])

(mf/defc tabs-wrapper*
  [{:keys [token tokens tab toggle] :rest props}]
  [:*
   [:div {:class (stl/css :title-bar)}
    [:div {:class (stl/css :title)} (tr "labels.typography")]
    [:& radio-buttons {:class (stl/css :listing-options)
                       :selected (d/name tab)
                       :on-change toggle
                       :name "reference-composite-tab"}
     [:& radio-button {:icon i/layers
                       :value "composite"
                       :title (tr "workspace.tokens.individual-tokens")
                       :id "composite-opt"}]
     [:& radio-button {:icon i/tokens
                       :value "reference"
                       :title (tr "workspace.tokens.use-reference")
                       :id "reference-opt"}]]]
   [:div {:class (stl/css :inputs-wrapper)}
    (if (= tab :composite)
      [:> composite-form* {:token token
                           :tokens tokens}]

      [:> reference-form* {:token token
                           :tokens tokens}])]])

;; SCHEMA

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
       [:font-family {:optional true} [:maybe :string]]
       [:font-size {:optional true} [:maybe :string]]
       [:font-weight {:optional true} [:maybe :string]]
       [:line-height {:optional true} [:maybe :string]]
       [:letter-spacing {:optional true} [:maybe :string]]
       [:text-case {:optional true} [:maybe :string]]
       [:text-decoration {:optional true} [:maybe :string]]
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

    [:fn {:error/field [:value :line-height]
          :error/fn #(tr "workspace.tokens.composite-line-height-needs-font-size")}
     (fn [{:keys [value]}]
       (let [line-heigh (get value :line-height)
             font-size (get value :font-size)]
         (if (and line-heigh (not font-size))
           false
           true)))]

    ;; This error does not shown on interface, it's just to avoid saving empty composite tokens
    ;; We don't need to translate it.
    [:fn {:error/fn (fn [_] "At least one composite field must be set")
          :error/field :value}
     (fn [attrs]
       (let [result (reduce-kv (fn [_ _ v]
                                 (if (str/empty? v)
                                   false
                                   (reduced true)))
                               false
                               (get attrs :value))]
         result))]]))

(mf/defc form*
  [{:keys [token] :as props}]
  (let [initial
        (mf/with-memo [token]
          (let [value (:value token)
                processed-value
                (cond
                  (string? value)
                  {:reference value}

                  (map? value)
                  (let [value (cond-> value
                                (:font-family value)
                                (update :font-family cto/join-font-family))]
                    (select-keys value
                                 [:font-family
                                  :font-size
                                  :font-weight
                                  :line-height
                                  :letter-spacing
                                  :text-case
                                  :text-decoration]))
                  :else
                  {})]

            {:name        (:name token "")
             :value       processed-value
             :description (:description token "")}))
        props (mf/spread-props props {:initial initial
                                      :make-schema make-schema
                                      :token token
                                      :validator validate-typography-token
                                      :type :composite
                                      :input-component tabs-wrapper*})]
    [:> generic/form* props]))
