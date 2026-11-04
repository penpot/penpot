;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-pill-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.ui.workspace.tokens.management.token-pill :as wtp]
   [app.util.i18n :refer [tr]]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private pill-state @#'wtp/pill-state)
(def ^:private tooltip-info @#'wtp/tooltip-info)

(def ^:private radius
  (ctob/make-token :name "radius.md" :type :border-radius :value "8"))

(def ^:private spacing
  (ctob/make-token :name "spacing.md" :type :spacing :value "16"))

(def ^:private width
  (ctob/make-token :name "width.md" :type :dimensions :value "100"))

(def ^:private active-theme-tokens
  {"radius.md" (assoc radius :resolved-value 8)
   "spacing.md" (assoc spacing :resolved-value 16)
   "width.md" (assoc width :resolved-value 100)})

(defn- with-token
  [shape token attributes]
  (cto/apply-token-to-shape {:shape shape :token token :attributes attributes}))

(defn- state
  [token selected-shapes & {:keys [tokens is-selected-inside-layout]
                            :or {tokens active-theme-tokens}}]
  (pill-state token tokens selected-shapes is-selected-inside-layout))

(defn- with-flags
  [flags f]
  (with-redefs [cf/flags flags]
    (f)))

(def ^:private no-tokenscript-flags (disj cf/flags :tokenscript))
(def ^:private tokenscript-flags (conj cf/flags :tokenscript))

(t/deftest applied-status
  (t/testing "without a selection the token counts as fully applied"
    (t/is (= {:is-applied false
              :is-half-applied false
              :is-disabled false
              :status-icon-id "token-status-full"}
             (select-keys (state radius [])
                          [:is-applied :is-half-applied :is-disabled :status-icon-id]))))

  (t/testing "applied to every attribute of every selected shape"
    (let [result (state radius [(with-token (ths/sample-shape :rect1) radius cto/border-radius-keys)
                                (with-token (ths/sample-shape :rect2) radius cto/border-radius-keys)])]
      (t/is (true? (:is-applied result)))
      (t/is (false? (:is-half-applied result)))
      (t/is (= "token-status-full" (:status-icon-id result)))))

  (t/testing "applied to only some of the attributes"
    (let [result (state radius [(with-token (ths/sample-shape :rect1) radius #{:r1})])]
      (t/is (true? (:is-applied result)))
      (t/is (true? (:is-half-applied result)))
      (t/is (= "token-status-partial" (:status-icon-id result)))))

  (t/testing "applied to only some of the selected shapes"
    (let [result (state radius [(with-token (ths/sample-shape :rect1) radius cto/border-radius-keys)
                                (ths/sample-shape :rect2)])]
      (t/is (true? (:is-half-applied result)))
      (t/is (= "token-status-partial" (:status-icon-id result)))))

  (t/testing "another token on the same attributes does not count"
    (let [other  (ctob/make-token :name "radius.lg" :type :border-radius :value "16")
          result (state radius [(with-token (ths/sample-shape :rect1) other #{:r1})])]
      (t/is (false? (:is-applied result)))
      (t/is (= "token-status-non-applied" (:status-icon-id result)))))

  (t/testing "color tokens count all their attributes, not only the fill"
    (let [color (ctob/make-token :name "brand" :type :color :value "#ff0000")]
      (t/is (true? (:is-half-applied
                    (state color [(with-token (ths/sample-shape :rect1) color #{:fill})]
                           :tokens {"brand" color}))))
      (t/is (false? (:is-half-applied
                     (state color [(with-token (ths/sample-shape :rect1) color #{:fill :stroke-color})]
                            :tokens {"brand" color})))))))

(t/deftest disabled-status
  (t/testing "a token that applies to none of the selected shapes is disabled"
    (t/is (true? (:is-disabled (state radius [(ths/sample-shape :circle1 :type :circle)]))))
    (t/is (true? (:is-disabled (state spacing [(ths/sample-shape :rect1)])))))

  (t/testing "one shape the token applies to is enough"
    (t/is (false? (:is-disabled (state radius [(ths/sample-shape :circle1 :type :circle)
                                               (ths/sample-shape :rect1)])))))

  (t/testing "spacing applies to boards with a layout"
    (t/is (false? (:is-disabled (state spacing [(ths/sample-shape :board1 :type :frame :layout :flex)])))))

  (t/testing "spacing applies as margin to shapes inside a layout"
    (t/is (false? (:is-disabled (state spacing [(ths/sample-shape :rect1)]
                                       :is-selected-inside-layout true)))))

  (t/testing "an applied token is never disabled"
    (t/is (false? (:is-disabled (state radius [(with-token (ths/sample-shape :circle1 :type :circle)
                                                 radius cto/border-radius-keys)]))))))

(t/deftest error-status
  (t/testing "a valid token has no errors"
    (let [result (state radius [])]
      (t/is (false? (:has-errors result)))
      (t/is (nil? (:error-message result)))))

  (t/testing "a reference to a token outside the active sets"
    (with-flags no-tokenscript-flags
      #(let [token  (ctob/make-token :name "radius.ref" :type :border-radius :value "{radius.missing}")
             result (state token [])]
         (t/is (true? (:has-missing-reference result)))
         (t/is (false? (:has-invalid-value result)))
         (t/is (true? (:has-errors result)))
         (t/is (nil? (:error-message result)))))

    (with-flags no-tokenscript-flags
      #(let [token (ctob/make-token :name "radius.ref" :type :border-radius :value "{radius.md}")]
         (t/is (false? (:has-errors (state token []))))))

    (with-flags tokenscript-flags
      #(let [error  {:error/code :error.style-dictionary/missing-reference
                     :message "Missing reference"}
             token  (ctob/make-token :name "radius.ref" :type :border-radius :value "{radius.missing}")
             result (state token [] :tokens {"radius.ref" (assoc token :errors [error])})]
         (t/is (true? (:has-missing-reference result)))
         (t/is (true? (:has-invalid-value result)))
         (t/is (= "Missing reference" (:error-message result))))))

  (t/testing "an invalid value only shows its own message with tokenscript"
    (let [error  {:error/code :error.style-dictionary/invalid-token-value
                  :message "Invalid value"}
          tokens {"radius.md" (assoc radius :errors [error])}]
      (with-flags no-tokenscript-flags
        #(let [result (state radius [] :tokens tokens)]
           (t/is (true? (:has-invalid-value result)))
           (t/is (true? (:has-errors result)))
           (t/is (nil? (:error-message result)))))
      (with-flags tokenscript-flags
        #(t/is (= "Invalid value" (:error-message (state radius [] :tokens tokens)))))))

  (t/testing "a name collision shows its message"
    (with-flags no-tokenscript-flags
      #(let [error  {:error/code :error.token/name-collision
                     :error/value "radius.md"
                     :error/fn (fn [value] (str "Collides: " value))}
             result (state radius [] :tokens {"radius.md" (assoc radius :errors [error])})]
         (t/is (true? (:has-errors result)))
         (t/is (= "Collides: radius.md" (:error-message result)))))))

(defn- tooltip
  [token shapes & {:keys [tokens is-viewer] :or {tokens active-theme-tokens}}]
  (tooltip-info token (get tokens (:name token)) (first shapes)
                (pill-state token tokens shapes false)
                is-viewer))

(t/deftest tooltip-messages
  (t/testing "errors replace the token details"
    (with-flags no-tokenscript-flags
      #(let [token (ctob/make-token :name "radius.ref" :type :border-radius :value "{radius.missing}")]
         (t/is (= {:message (tr "workspace.tokens.ref-not-valid" "radius.ref")}
                  (tooltip token [])))))

    (with-flags no-tokenscript-flags
      #(let [error {:error/code :error.style-dictionary/invalid-token-value}]
         (t/is (= {:message (tr "workspace.tokens.value-not-valid")}
                  (tooltip radius [] :tokens {"radius.md" (assoc radius :errors [error])}))))))

  (t/testing "a valid token shows its value and resolved value"
    (t/is (= {:name "radius.md"
              :value "8"
              :resolved-value "8"
              :has-more-options false
              :applied-to nil}
             (tooltip radius []))))

  (t/testing "the resolved value comes from the active themes"
    (let [token (ctob/make-token :name "radius.ref" :type :border-radius :value "{radius.md}")]
      (t/is (= "8" (:resolved-value
                    (tooltip token [] :tokens (assoc active-theme-tokens
                                                     "radius.ref" (assoc token :resolved-value 8)))))))))

(t/deftest tooltip-applied-to
  (t/testing "editors don't see where the token is applied"
    (t/is (nil? (:applied-to (tooltip radius [(with-token (ths/sample-shape :rect1) radius cto/border-radius-keys)])))))

  (t/testing "viewers see the attributes of the first selected shape"
    (t/is (= (str "- Border Radius: " (tr "labels.all"))
             (:applied-to (tooltip radius [(with-token (ths/sample-shape :rect1) radius cto/border-radius-keys)]
                                   :is-viewer true))))

    (t/is (= "- Border Radius: Top Left"
             (:applied-to (tooltip radius [(with-token (ths/sample-shape :rect1) radius #{:r1})]
                                   :is-viewer true)))))

  (t/testing "dimension tokens list their attributes by category"
    (t/is (= "- Sizing: Width."
             (:applied-to (tooltip width [(with-token (ths/sample-shape :rect1) width #{:width})]
                                   :is-viewer true)))))

  (t/testing "nothing is listed when the token isn't applied to the first shape"
    (t/is (nil? (:applied-to (tooltip radius [(ths/sample-shape :rect1)
                                              (with-token (ths/sample-shape :rect2)
                                                radius cto/border-radius-keys)]
                                      :is-viewer true))))))
