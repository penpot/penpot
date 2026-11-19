;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.typography-form-test
  (:require
   [app.common.schema :as sm]
   [app.common.schema.messages :as csm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.management.forms.typography :as wtty]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(def ^:private make-schema @#'wtty/make-schema)
(def ^:private form-value @#'wtty/composite-value->form-value)
(def ^:private check-self-reference @#'wtty/check-typography-token-self-reference)
(def ^:private validate-token @#'wtty/validate-typography-token)

(def ^:private token
  (ctob/make-token :name "type.body" :type :typography :value {:font-size "16"}))

(defn- schema
  [tab]
  (make-schema (ctob/get-token-path token)
               (ctob/tokens-tree {(:name token) token})
               tab))

(defn- form-data
  [value]
  {:name (:name token) :description "" :value value})

(defn- errors
  [tab data]
  (csm/collect-schema-errors (schema tab) [] {:data data}))

(defn- valid?
  [tab data]
  (sm/validate (schema tab) data))

(t/deftest composite-tab
  (t/testing "one field is enough"
    (t/is (valid? :composite (form-data {:font-size "16"}))))

  (t/testing "at least one field must be set"
    (t/is (not (valid? :composite (form-data {}))))
    (t/is (not (valid? :composite (form-data {:font-size "" :font-weight ""})))))

  (t/testing "a line height needs a font size"
    (t/is (= (tr "errors.tokens.composite-line-height-needs-font-size")
             (get-in (errors :composite (form-data {:line-height "1.5"}))
                     [:value :line-height :message])))
    (t/is (valid? :composite (form-data {:line-height "1.5" :font-size "16"})))))

(t/deftest reference-tab
  (t/testing "a reference is required"
    (t/is (not (valid? :reference (form-data {:reference ""}))))
    (t/is (valid? :reference (form-data {:reference "{type.heading}"}))))

  (t/testing "the token can't reference itself"
    (t/is (= (tr "errors.tokens.self-reference")
             (get-in (errors :reference (form-data {:reference "{type.body}"}))
                     [:value :reference :message])))))

(t/deftest composite-self-reference
  (let [self-reference (wte/get-error-code :error.token/direct-self-reference)]
    (t/testing "the error names the field that references the token"
      (t/is (= (assoc self-reference :typography-key :font-size)
               (check-self-reference {:name "type.body" :value {:font-size "{type.body}"}}))))

    (t/testing "each font family is checked"
      (t/is (= (assoc self-reference :typography-key :font-family)
               (check-self-reference {:name "type.body" :value {:font-family ["Inter" "{type.body}"]}}))))

    (t/testing "references to other tokens pass"
      (t/is (nil? (check-self-reference {:name "type.body" :value {:font-size "{font.size.md}"}}))))))

(t/deftest submit-without-fields
  (t/async
    done
    (->> (validate-token {:token-name "type.body" :token-value nil :tokens {}})
         (rx/subs! (fn [_]
                     (t/is false "a token without fields must be rejected, not saved")
                     (done))
                   (fn [error]
                     (t/is (= {:errors [(wte/get-error-code :error.token/empty-input)]} error))
                     (done))))))

(t/deftest initial-form-value
  (t/testing "font families show as one comma separated field"
    (t/is (= {:font-family "Inter, Arial" :font-size "16"}
             (form-value {:font-family ["Inter" "Arial"] :font-size "16"}))))

  (t/testing "only the typography fields are kept"
    (t/is (= {:line-height "1.5"}
             (form-value {:line-height "1.5" :paragraph-spacing "4"})))))
