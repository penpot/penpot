;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.logic.token-remapping-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.remapping :as dwtr]
   [cljs.test :as t :include-macros true]))

(defn setup-file-with-tokens
  "Setup a test file with tokens and shapes that use those tokens"
  []
  (let [color-token {:id (cthi/new-id! :color-primary)
                     :name "color.primary"
                     :value "#FF0000"
                     :type :color}
        alias-token {:id (cthi/new-id! :color-secondary)
                     :name "color.secondary"
                     :value "{color.primary}"
                     :type :color}]
    (-> (cthf/sample-file :file-1 :page-label :page-1)
        (ctho/add-rect :rect-1)
        (ctho/add-rect :rect-2)
        (assoc-in [:data :tokens-lib]
                  (-> (ctob/make-tokens-lib)
                      (ctob/add-theme (ctob/make-token-theme :name "Theme A" :sets #{"Set A"}))
                      (ctob/set-active-themes #{"/Theme A"})
                      (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-a)
                                                         :name "Set A"))
                      (ctob/add-token (cthi/id :set-a)
                                      (ctob/make-token color-token))
                      (ctob/add-token (cthi/id :set-a)
                                      (ctob/make-token alias-token))))
        ;; Apply the token to rect-1
        (ctht/apply-token-to-shape :rect-1 "color.primary" [:fill] [:fill] "#FF0000"))))

(t/deftest test-scan-token-value-references
  (t/testing "should extract token references from alias values"
    (let [token {:id (cthi/id :color-primary)
                 :name "color.secondary"
                 :value "{color.primary}"
                 :type :color}
          references (dwtr/scan-token-value-references token "color.primary")]

      (t/is (= 1 (count references)))
      (let [ref (first references)]
        (t/is (= :token-alias (:type ref)))
        (t/is (= (cthi/id :color-secondary) (:source-token-id ref)))
        (t/is (= "color.primary" (:referenced-token-name ref)))))))

(t/deftest test-scan-workspace-token-references
  (t/testing "should find applied token references"
    (let [file (setup-file-with-tokens)
          file-data (:data file)
          scan-results (dwtr/scan-workspace-token-references file-data "color.primary")]

      (t/is (= 1 (count (:applied-tokens scan-results))))
      (t/is (= 1 (count (:token-aliases scan-results))))
      (t/is (= 2 (:total-references scan-results)))

      ;; Check applied token reference
      (let [applied-ref (first (:applied-tokens scan-results))]
        (t/is (= :applied-token (:type applied-ref)))
        (t/is (= "color.primary" (:token-name applied-ref)))
        (t/is (= :fill (:attribute applied-ref))))

      ;; Check alias reference
      (let [alias-ref (first (:token-aliases scan-results))]
        (t/is (= :token-alias (:type alias-ref)))
        (t/is (= (cthi/id :color-secondary) (:source-token-id alias-ref)))
        (t/is (= "color.primary" (:referenced-token-name alias-ref)))))))

(t/deftest test-update-token-value-references
  (t/testing "should update token references in alias values"
    (let [old-value "{color.primary}"
          new-value (cto/update-token-value-references old-value "color.primary" "brand.primary")]
      (t/is (= "{brand.primary}" new-value))))

  (t/testing "should update multiple references"
    (let [old-value "calc({spacing.base} + {spacing.base})"
          new-value (cto/update-token-value-references old-value "spacing.base" "spacing.foundation")]
      (t/is (= "calc({spacing.foundation} + {spacing.foundation})" new-value))))

  (t/testing "should not update partial matches"
    (let [old-value "{color.primary.light}"
          new-value (cto/update-token-value-references old-value "color.primary" "brand.primary")]
      (t/is (= "{color.primary.light}" new-value)))))

(t/deftest test-count-token-references
  (t/testing "should count total references to a token"
    (let [file (setup-file-with-tokens)
          file-data (:data file)
          count (dwtr/count-token-references file-data "color.primary")]
      (t/is (= 2 count)))))

(t/deftest test-validate-token-remapping
  (t/testing "should validate remapping parameters"
    (t/testing "empty name should be invalid"
      (let [result (dwtr/validate-token-remapping "color.primary" "")]
        (t/is (false? (:valid? result)))
        (t/is (= :invalid-name (:error result)))))

    (t/testing "same name should be invalid"
      (let [result (dwtr/validate-token-remapping "color.primary" "color.primary")]
        (t/is (false? (:valid? result)))
        (t/is (= :no-change (:error result)))))

    (t/testing "valid new name should be valid"
      (let [result (dwtr/validate-token-remapping "color.primary" "brand.primary")]
        (t/is (true? (:valid? result)))))))
