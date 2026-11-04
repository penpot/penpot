;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.shadow-form-test
  (:require
   [app.common.schema :as sm]
   [app.common.schema.messages :as csm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.management.forms.shadow :as wtsh]
   [app.util.i18n :refer [tr]]
   [cljs.test :as t :include-macros true]))

(def ^:private make-schema @#'wtsh/make-schema)
(def ^:private form-value @#'wtsh/make-default-value)
(def ^:private check-self-reference @#'wtsh/check-shadow-token-self-reference)

(def ^:private shadow
  (assoc @#'wtsh/default-token-shadow :color "#000000"))

(def ^:private token
  (ctob/make-token :name "shadow.md" :type :shadow :value [shadow]))

(defn- schema
  [tab]
  (make-schema (ctob/get-token-path token)
               (ctob/tokens-tree {(:name token) token})
               tab))

(defn- form-data
  [value]
  {:name (:name token) :description "" :value value})

(defn- shadows
  [& shadows]
  (form-data {:reference nil :shadow (vec shadows)}))

(defn- errors
  [tab data]
  (csm/collect-schema-errors (schema tab) [] {:data data}))

(defn- valid?
  [tab data]
  (sm/validate (schema tab) data))

(t/deftest composite-tab
  (t/testing "a complete shadow is valid"
    (t/is (valid? :composite (shadows shadow))))

  (t/testing "blur can't be negative"
    (t/is (= (tr "workspace.tokens.shadow-token-blur-value-error")
             (get-in (errors :composite (shadows (assoc shadow :blur "-2")))
                     [:value :shadow 0 :blur :message]))))

  (t/testing "spread can be negative"
    (t/is (valid? :composite (shadows (assoc shadow :spread "-2")))))

  (t/testing "blur and spread can stay blank"
    (t/is (valid? :composite (shadows (assoc shadow :blur "" :spread "")))))

  (t/testing "every shadow needs its offsets and color"
    (t/is (not (valid? :composite (shadows shadow (assoc shadow :color "")))))
    (t/is (not (valid? :composite (shadows (assoc shadow :offset-x "")))))
    (t/is (contains? (errors :composite (shadows (dissoc shadow :color))) :value))))

(t/deftest reference-tab
  (t/testing "a reference is required"
    (t/is (not (valid? :reference (form-data {:reference "" :shadow []}))))
    (t/is (valid? :reference (form-data {:reference "{shadow.sm}" :shadow []}))))

  (t/testing "the token can't reference itself"
    (t/is (= (tr "errors.tokens.self-reference")
             (get-in (errors :reference (form-data {:reference "{shadow.md}" :shadow []}))
                     [:value :reference :message])))))

(t/deftest composite-self-reference
  (t/testing "the error names the shadow and field that reference the token"
    (t/is (= (assoc (wte/get-error-code :error.token/direct-self-reference)
                    :shadow-key :color
                    :shadow-index 1)
             (check-self-reference
              {:name "shadow.md"
               :value [shadow (assoc shadow :color "{shadow.md}")]}))))

  (t/testing "references to other tokens pass"
    (t/is (nil? (check-self-reference
                 {:name "shadow.md"
                  :value [(assoc shadow :color "{color.black}")]})))))

(t/deftest initial-form-value
  (t/testing "a reference opens on the reference tab"
    (t/is (= {:reference "{shadow.sm}" :shadow []}
             (form-value "{shadow.sm}"))))

  (t/testing "blank or missing blur and spread show as 0"
    (t/is (= {:reference nil :shadow [(assoc shadow :blur "0" :spread "0")]}
             (form-value [(assoc shadow :blur "" :spread "  ")])
             (form-value [(dissoc shadow :blur :spread)]))))

  (t/testing "a new token starts with one default shadow"
    (t/is (= {:reference nil :shadow [@#'wtsh/default-token-shadow]}
             (form-value nil)))))
