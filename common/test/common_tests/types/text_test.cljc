;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.text-test
  (:require

   [app.common.types.shape :as cts]
   [app.common.types.text :as cttx]
   [clojure.test :as t :include-macros true]))

(def content-base
  (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
      (get :content)
      (cttx/change-text "hello world")))

(def content-changed-text
  (assoc-in content-base [:children 0 :children 0 :children 0 :text] "changed"))

(def content-changed-attr
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] "32"))

(def content-changed-both
  (-> content-base
      (assoc-in [:children 0 :children 0 :children 0 :text] "changed")
      (assoc-in  [:children 0 :children 0 :children 0 :font-size] "32")))

(def line
  (get-in content-base [:children 0 :children 0 :children 0]))

(def content-changed-structure
  (update-in content-base [:children 0 :children 0 :children]
             #(conj % (assoc line :font-weight "700"))))


(def content-changed-structure-same-attrs
  (update-in content-base [:children 0 :children 0 :children] #(conj % line)))

(t/deftest test-get-diff-type
  (let [diff-text                 (cttx/get-diff-type content-base content-changed-text)
        diff-attr                 (cttx/get-diff-type content-base content-changed-attr)
        diff-both                 (cttx/get-diff-type content-base content-changed-both)
        diff-structure            (cttx/get-diff-type content-base content-changed-structure)
        diff-structure-same-attrs (cttx/get-diff-type content-base content-changed-structure-same-attrs)]

    (t/is (= #{:text-content-text} diff-text))
    (t/is (= #{:text-content-attribute} diff-attr))
    (t/is (= #{:text-content-text :text-content-attribute} diff-both))
    (t/is (= #{:text-content-structure} diff-structure))
    (t/is (= #{:text-content-structure} diff-structure-same-attrs))))


(t/deftest test-get-diff-attrs
  (let [attrs-text                 (cttx/get-diff-attrs content-base content-changed-text)
        attrs-attr                 (cttx/get-diff-attrs content-base content-changed-attr)
        attrs-both                 (cttx/get-diff-attrs content-base content-changed-both)
        attrs-structure            (cttx/get-diff-attrs content-base content-changed-structure)
        attrs-structure-same-attrs (cttx/get-diff-attrs content-base content-changed-structure-same-attrs)]

    (t/is (= #{} attrs-text))
    (t/is (= #{:font-size} attrs-attr))
    (t/is (= #{:font-size} attrs-both))
    (t/is (= #{} attrs-structure))
    (t/is (= #{} attrs-structure-same-attrs))))


(t/deftest test-equal-structure
  (t/is (true? (cttx/equal-structure? content-base content-changed-text)))
  (t/is (true? (cttx/equal-structure? content-base content-changed-attr)))
  (t/is (true? (cttx/equal-structure? content-base content-changed-both)))
  (t/is (false? (cttx/equal-structure? content-base content-changed-structure))))


(t/deftest test-copy-text-keys
  (let [copy-base-to-changed-text (cttx/copy-text-keys content-base content-changed-text)
        copy-changed-text-to-base (cttx/copy-text-keys content-changed-text content-base)

        copy-base-to-changed-attr (cttx/copy-text-keys content-base content-changed-attr)

        copy-changes-text-to-changed-attr (cttx/copy-text-keys content-changed-text content-changed-attr)
        updates-text-in-changed-attr (assoc-in content-changed-attr [:children 0 :children 0 :children 0 :text] "changed")]

    ;; If we copy the text of the base to the content-changed-text, the result is equal than the base
    (t/is (= copy-base-to-changed-text content-base))

    ;; If we copy the text of the content-changed-text to the base, the result is equal than the content-changed-text
    (t/is (= copy-changed-text-to-base content-changed-text))

    ;; If we copy the text of the base to the content-changed-attr, it doesn't nothing because the text were equal
    (t/is (= copy-base-to-changed-attr content-changed-attr))

    ;; If we copy the text of the content-changed-text to the content-changed-attr, it keeps the changes on the attrs
    ;; and the changes on the texts
    (t/is (= copy-changes-text-to-changed-attr updates-text-in-changed-attr))))


(t/deftest test-copy-attrs-keys
  (let [attrs (-> (cttx/get-first-paragraph-text-attrs content-changed-structure-same-attrs)
                  (assoc :font-size "32"))
        updated (cttx/copy-attrs-keys content-changed-structure-same-attrs attrs)
        get-font-sizes (fn get-font-sizes [fonts item]
                         (let [font-size (:font-size item)
                               fonts (if font-size (conj fonts font-size) fonts)]
                           (if (seq (:children item))
                             (reduce get-font-sizes fonts (:children item))
                             fonts)))
        original-font-sizes (get-font-sizes [] content-changed-structure-same-attrs)
        updated-font-sizes (get-font-sizes [] updated)]

    (t/is (every? #(= % "14") original-font-sizes))
    (t/is (every? #(= % "32") updated-font-sizes))))
