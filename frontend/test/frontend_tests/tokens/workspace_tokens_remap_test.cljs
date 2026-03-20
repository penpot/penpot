;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.workspace-tokens-remap-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.remapping :as remap]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(def token-set-name "remap-test-set")
(def token-theme-name "remap-test-theme")

(defn- make-base-file []
  (-> (cthf/sample-file :file-1 :page-label :page-1)
      (ctho/add-rect :rect-shape)
      (ctho/add-text :text-shape "Sample text")))

(defn- attach-token-set [file tokens]
  (let [set-id (cthi/new-id! :token-set)
        tokens-lib (reduce
                    (fn [lib token]
                      (ctob/add-token lib set-id (ctob/make-token token)))
                    (-> (ctob/make-tokens-lib)
                        (ctob/add-set (ctob/make-token-set :id set-id
                                                           :name token-set-name))
                        (ctob/add-theme (ctob/make-token-theme :name token-theme-name
                                                               :sets #{token-set-name}))
                        (ctob/set-active-themes #{(str "/" token-theme-name)}))
                    tokens)]
    (assoc-in file [:data :tokens-lib] tokens-lib)))

(defn- tokens-lib [file]
  (get-in file [:data :tokens-lib]))

(defn- find-token-entry [tokens-lib token-name]
  (when tokens-lib
    (some (fn [set]
            (let [set-id (ctob/get-id set)
                  tokens (ctob/get-tokens tokens-lib set-id)]
              (some (fn [[_ token]]
                      (when (= (:name token) token-name)
                        {:set-id set-id
                         :token token}))
                    tokens)))
          (ctob/get-sets tokens-lib))))

(defn- rename-token-in-file [file old-name new-name]
  (let [tokens-lib (tokens-lib file)]
    (if-let [{:keys [set-id token]} (find-token-entry tokens-lib old-name)]
      (let [tokens-lib' (ctob/update-token tokens-lib set-id (:id token) #(assoc % :name new-name))]
        (assoc-in file [:data :tokens-lib] tokens-lib'))
      file)))

(defn- alias-reference [token-name]
  (str "{" token-name "}"))

(defn- alias-token-for-case [{:keys [token]}]
  {:name (str (:name token) "-alias")
   :type (:type token)
   :value (alias-reference (:name token))})

(defn- file-for-case [{:keys [token attribute shape]}]
  (let [file (-> (make-base-file)
                 (attach-token-set [token]))]
    (toht/apply-token-to-shape file shape (:name token) #{attribute})))

(def token-remap-cases
  [{:case :boolean
    :token {:name "boolean-token" :type :boolean :value true}
    :attribute :visible
    :shape :rect-shape}

   {:case :border-radius
    :token {:name "border-radius-token" :type :border-radius :value "12"}
    :attribute :r1
    :shape :rect-shape}

   {:case :shadow
    :token {:name "shadow-token"
            :type :shadow
            :value [{:offset-x 0
                     :offset-y 1
                     :blur 2
                     :spread 0
                     :color "rgba(0,0,0,0.5)"
                     :inset false}]}
    :attribute :shadow
    :shape :rect-shape}

   {:case :color
    :token {:name "color-token" :type :color :value "#ff0000"}
    :attribute :fill
    :shape :rect-shape}

   {:case :dimensions
    :token {:name "dimensions-token" :type :dimensions :value "100px"}
    :attribute :width
    :shape :rect-shape}

   {:case :font-family
    :token {:name "font-family-token"
            :type :font-family
            :value ["Arial" "Helvetica"]}
    :attribute :font-family
    :shape :text-shape}

   {:case :font-size
    :token {:name "font-size-token" :type :font-size :value "16px"}
    :attribute :font-size
    :shape :text-shape}

   {:case :letter-spacing
    :token {:name "letter-spacing-token" :type :letter-spacing :value "1"}
    :attribute :letter-spacing
    :shape :text-shape}

   {:case :number
    :token {:name "number-token" :type :number :value "42"}
    :attribute :line-height
    :shape :text-shape}

   {:case :opacity
    :token {:name "opacity-token" :type :opacity :value 0.5}
    :attribute :opacity
    :shape :rect-shape}

   {:case :other
    :token {:name "other-token" :type :other :value "misc"}
    :attribute :custom-data
    :shape :rect-shape}

   {:case :rotation
    :token {:name "rotation-token" :type :rotation :value 45}
    :attribute :rotation
    :shape :rect-shape}

   {:case :sizing
    :token {:name "sizing-token" :type :sizing :value "200px"}
    :attribute :height
    :shape :rect-shape}

   {:case :spacing
    :token {:name "spacing-token" :type :spacing :value "8"}
    :attribute :spacing
    :shape :rect-shape}

   {:case :string
    :token {:name "string-token" :type :string :value "hello"}
    :attribute :string-content
    :shape :text-shape}

   {:case :stroke-width
    :token {:name "stroke-width-token" :type :stroke-width :value "2"}
    :attribute :stroke-width
    :shape :rect-shape}

   {:case :text-case
    :token {:name "text-case-token" :type :text-case :value "uppercase"}
    :attribute :text-transform
    :shape :text-shape}

   {:case :text-decoration
    :token {:name "text-decoration-token" :type :text-decoration :value "underline"}
    :attribute :text-decoration
    :shape :text-shape}

   {:case :font-weight
    :token {:name "font-weight-token" :type :font-weight :value "bold"}
    :attribute :font-weight
    :shape :text-shape}

   {:case :typography
    :token {:name "typography-token"
            :type :typography
            :value {:font-size "18px"
                    :font-family [(:font-id txt/default-text-attrs) "Arial"]
                    :font-weight "600"
                    :line-height "20px"
                    :letter-spacing "1"
                    :text-case "uppercase"
                    :text-decoration "underline"}}
    :attribute :typography
    :shape :text-shape}])

(def token-case-by-type
  (into {} (map (juxt :case identity) token-remap-cases)))

(defn- fetch-token-case [case-key]
  (or (get token-case-by-type case-key)
      (throw (ex-info "Unknown token case" {:case case-key}))))

(defn- run-token-remap-test! [token-case done]
  (let [{:keys [token attribute shape]} token-case
        old-name (:name token)
        new-name (str old-name "-renamed")
        file (-> (file-for-case token-case)
                 (rename-token-in-file old-name new-name))
        store (ths/setup-store file)
        events [(remap/remap-tokens old-name new-name)]]
    (tohs/run-store-async
     store done events
     (fn [new-state]
       (let [file' (ths/get-file-from-state new-state)
             shape' (cths/get-shape file' shape)
             applied-name (get-in shape' [:applied-tokens attribute])]
         (t/is (= new-name applied-name)
               (str "attribute " attribute " now references renamed token"))
         (t/is (not-any? #(= old-name %) (vals (or (:applied-tokens shape') {})))
               "old token name removed from applied tokens"))))))

(defn- run-alias-remap-test! [token-case done]
  (let [{:keys [token]} token-case
        alias-token (alias-token-for-case token-case)
        alias-name (:name alias-token)
        old-name (:name token)
        new-name (str old-name "-renamed")
        file (-> (make-base-file)
                 (attach-token-set [token alias-token]))
        tokens-lib-before (tokens-lib file)
        set-id (some-> (ctob/get-set-by-name tokens-lib-before token-set-name) ctob/get-id)
        alias-before (ctob/get-token-by-name tokens-lib-before token-set-name alias-name)
        alias-id (:id alias-before)
        file' (rename-token-in-file file old-name new-name)
        store (ths/setup-store file')]
    (tohs/run-store-async
     store done [(remap/remap-tokens old-name new-name)]
     (fn [new-state]
       (let [file'' (ths/get-file-from-state new-state)
             tokens-lib' (tokens-lib file'')
             updated-alias (ctob/get-token tokens-lib' set-id alias-id)]
         (t/is (= (alias-reference new-name) (:value updated-alias))
               (str "alias for " alias-name " updated to new token name")))))))

(defn- define-remap-test [case-key test-fn]
  (t/async done
    (test-fn (fetch-token-case case-key) done)))

;; Direct remap tests
(t/deftest remap-boolean-token
  (define-remap-test :boolean run-token-remap-test!))

(t/deftest remap-border-radius-token
  (define-remap-test :border-radius run-token-remap-test!))

(t/deftest remap-shadow-token
  (define-remap-test :shadow run-token-remap-test!))

(t/deftest remap-color-token
  (define-remap-test :color run-token-remap-test!))

(t/deftest remap-dimensions-token
  (define-remap-test :dimensions run-token-remap-test!))

(t/deftest remap-font-family-token
  (define-remap-test :font-family run-token-remap-test!))

(t/deftest remap-font-size-token
  (define-remap-test :font-size run-token-remap-test!))

(t/deftest remap-letter-spacing-token
  (define-remap-test :letter-spacing run-token-remap-test!))

(t/deftest remap-number-token
  (define-remap-test :number run-token-remap-test!))

(t/deftest remap-opacity-token
  (define-remap-test :opacity run-token-remap-test!))

(t/deftest remap-other-token
  (define-remap-test :other run-token-remap-test!))

(t/deftest remap-rotation-token
  (define-remap-test :rotation run-token-remap-test!))

(t/deftest remap-sizing-token
  (define-remap-test :sizing run-token-remap-test!))

(t/deftest remap-spacing-token
  (define-remap-test :spacing run-token-remap-test!))

(t/deftest remap-string-token
  (define-remap-test :string run-token-remap-test!))

(t/deftest remap-stroke-width-token
  (define-remap-test :stroke-width run-token-remap-test!))

(t/deftest remap-text-case-token
  (define-remap-test :text-case run-token-remap-test!))

(t/deftest remap-text-decoration-token
  (define-remap-test :text-decoration run-token-remap-test!))

(t/deftest remap-font-weight-token
  (define-remap-test :font-weight run-token-remap-test!))

(t/deftest remap-typography-token
  (define-remap-test :typography run-token-remap-test!))

;; Alias remap tests
(t/deftest remap-boolean-alias
  (define-remap-test :boolean run-alias-remap-test!))

(t/deftest remap-border-radius-alias
  (define-remap-test :border-radius run-alias-remap-test!))

(t/deftest remap-shadow-alias
  (define-remap-test :shadow run-alias-remap-test!))

(t/deftest remap-color-alias
  (define-remap-test :color run-alias-remap-test!))

(t/deftest remap-dimensions-alias
  (define-remap-test :dimensions run-alias-remap-test!))

(t/deftest remap-font-family-alias
  (define-remap-test :font-family run-alias-remap-test!))

(t/deftest remap-font-size-alias
  (define-remap-test :font-size run-alias-remap-test!))

(t/deftest remap-letter-spacing-alias
  (define-remap-test :letter-spacing run-alias-remap-test!))

(t/deftest remap-number-alias
  (define-remap-test :number run-alias-remap-test!))

(t/deftest remap-opacity-alias
  (define-remap-test :opacity run-alias-remap-test!))

(t/deftest remap-other-alias
  (define-remap-test :other run-alias-remap-test!))

(t/deftest remap-rotation-alias
  (define-remap-test :rotation run-alias-remap-test!))

(t/deftest remap-sizing-alias
  (define-remap-test :sizing run-alias-remap-test!))

(t/deftest remap-spacing-alias
  (define-remap-test :spacing run-alias-remap-test!))

(t/deftest remap-string-alias
  (define-remap-test :string run-alias-remap-test!))

(t/deftest remap-stroke-width-alias
  (define-remap-test :stroke-width run-alias-remap-test!))

(t/deftest remap-text-case-alias
  (define-remap-test :text-case run-alias-remap-test!))

(t/deftest remap-text-decoration-alias
  (define-remap-test :text-decoration run-alias-remap-test!))

(t/deftest remap-font-weight-alias
  (define-remap-test :font-weight run-alias-remap-test!))

(t/deftest remap-typography-alias
  (define-remap-test :typography run-alias-remap-test!))
