;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-forms-test
  (:require
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as wtgf]
   [app.main.ui.workspace.tokens.management.forms.shadow :as wtsh]
   [app.main.ui.workspace.tokens.management.forms.typography :as wtty]
   [app.main.ui.workspace.tokens.management.forms.validators :as wtv]
   [cljs.test :as t :include-macros true]))

(def ^:private check-shadow-self-reference @#'wtsh/check-shadow-token-self-reference)
(def ^:private check-empty-shadow @#'wtsh/check-empty-shadow-token)
(def ^:private shadow-form-value @#'wtsh/make-default-value)
(def ^:private default-shadow @#'wtsh/default-token-shadow)
(def ^:private check-typography-self-reference @#'wtty/check-typography-token-self-reference)
(def ^:private typography-form-value @#'wtty/composite-value->form-value)

(def ^:private empty-input (wte/get-error-code :error.token/empty-input))
(def ^:private self-reference (wte/get-error-code :error.token/direct-self-reference))

(def ^:private shadow
  {:offset-x "4" :offset-y "4" :blur "8" :spread "0" :color "#000000"})

(t/deftest check-empty-value
  (t/is (= empty-input (wtv/check-empty-value {:name "spacing.md" :value ""})))
  (t/is (= empty-input (wtv/check-empty-value {:name "spacing.md" :value "  "})))
  (t/is (nil? (wtv/check-empty-value {:name "spacing.md" :value "4"}))))

(t/deftest check-self-reference
  (t/is (= self-reference (wtv/check-self-reference "spacing.md" "{spacing.md} * 2")))
  (t/is (nil? (wtv/check-self-reference "spacing.md" "{spacing.sm} * 2")))

  (t/testing "any value of a collection"
    (t/is (= self-reference (wtv/check-coll-self-reference "font.body" ["Inter" "{font.body}"])))
    (t/is (nil? (wtv/check-coll-self-reference "font.body" ["Inter" "Arial"])))))

(t/deftest shadow-validation
  (t/testing "a shadow token without shadows is empty"
    (t/is (= empty-input (check-empty-shadow {:value []}))))

  (t/testing "a shadow missing one of its fields makes the token empty"
    (t/is (= empty-input (check-empty-shadow {:value [shadow (dissoc shadow :blur)]}))))

  (t/testing "complete shadows pass"
    (t/is (nil? (check-empty-shadow {:value [shadow shadow]}))))

  (t/testing "a self reference points at its shadow and field"
    (t/is (= (assoc self-reference :shadow-key :color :shadow-index 1)
             (check-shadow-self-reference
              {:name "shadow.md"
               :value [shadow (assoc shadow :color "{shadow.md}")]})))
    (t/is (nil? (check-shadow-self-reference
                 {:name "shadow.md"
                  :value [(assoc shadow :color "{color.black}")]})))))

(t/deftest shadow-form-value-test
  (t/testing "a reference opens the reference tab"
    (t/is (= {:reference "{shadow.md}" :shadow []}
             (shadow-form-value "{shadow.md}"))))

  (t/testing "blank or missing blur and spread become 0"
    (t/is (= {:reference nil
              :shadow [(assoc shadow :blur "0" :spread "0")]}
             (shadow-form-value [(assoc shadow :blur "" :spread "  ")])))
    (t/is (= {:reference nil
              :shadow [(assoc shadow :blur "0" :spread "0")]}
             (shadow-form-value [(dissoc shadow :blur :spread)]))))

  (t/testing "a new token starts with one default shadow"
    (t/is (= {:reference nil :shadow [default-shadow]}
             (shadow-form-value nil)))))

(t/deftest typography-validation
  (t/testing "a self reference points at its field"
    (t/is (= (assoc self-reference :typography-key :font-size)
             (check-typography-self-reference
              {:name "type.body" :value {:font-size "{type.body}"}}))))

  (t/testing "font families are checked one by one"
    (t/is (= (assoc self-reference :typography-key :font-family)
             (check-typography-self-reference
              {:name "type.body" :value {:font-family ["Inter" "{type.body}"]}}))))

  (t/testing "references to other tokens pass"
    (t/is (nil? (check-typography-self-reference
                 {:name "type.body" :value {:font-size "{font.size.md}"}})))))

(t/deftest typography-form-value-test
  (t/testing "font families become one comma separated field"
    (t/is (= {:font-family "Inter, Arial" :font-size "16"}
             (typography-form-value {:font-family ["Inter" "Arial"] :font-size "16"}))))

  (t/testing "only the typography fields are kept"
    (t/is (= {:line-height "1.5"}
             (typography-form-value {:line-height "1.5" :paragraph-spacing "4"})))))

(t/deftest get-value-for-validator
  (let [shadow-value {:reference "{shadow.md}" :shadow [shadow]}
        typography-value {:reference "{type.body}" :font-size "16"}]
    (t/testing "the reference tab validates the reference"
      (t/is (= "{shadow.md}" (wtgf/get-value-for-validator :reference shadow-value :shadow :indexed)))
      (t/is (= "{type.body}" (wtgf/get-value-for-validator :reference typography-value nil :composite))))

    (t/testing "the composite tab validates the shadows or the whole value"
      (t/is (= [shadow] (wtgf/get-value-for-validator :composite shadow-value :shadow :indexed)))
      (t/is (= typography-value (wtgf/get-value-for-validator :composite typography-value nil :composite))))

    (t/testing "single value tokens validate their value"
      (t/is (= "4" (wtgf/get-value-for-validator :composite "4" nil nil))))))
