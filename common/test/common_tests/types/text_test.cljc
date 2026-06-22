;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.text-test
  (:require

   [app.common.types.shape :as cts]
   [app.common.types.text :as cttx]
   [clojure.test :as t :include-macros true]))

(def content-base
  (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
      (get :content)
      (cttx/change-text "hello world")))

;; Normal happy-path changes

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

;; Special cases

;; Numeric value (legacy): number 14 should normalize to string "14",
;; which equals the default value in content-base
(def content-numeric-font-size-default
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] 14))

;; Numeric value (legacy): number 32 should normalize to string "32",
;; matching content-changed-attr (which uses string "32")
(def content-numeric-font-size-32
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] 32))

;; Attribute set to nil (removed)
(def content-nil-font-size
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] nil))

;; Attribute set to empty string
(def content-empty-string-font-size
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] ""))

;; Attribute set to empty vector
(def content-empty-list-font-size
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] []))

;; Attribute set to its default value (font-size default is "14")
(def content-default-font-size
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-size] "14"))

;; Non-text-node-attr key change (grow-type is not in text-node-attrs)
(def content-non-text-node-attr
  (assoc-in content-base [:children 0 :children 0 :children 0 :grow-type] "auto-height"))

;; Other text-node-attr categories
(def content-changed-font-family
  (assoc-in content-base [:children 0 :children 0 :children 0 :font-family] "Arial"))

(def content-changed-line-height
  (assoc-in content-base [:children 0 :children 0 :children 0 :line-height] "1.5"))

(def content-changed-letter-spacing
  (assoc-in content-base [:children 0 :children 0 :children 0 :letter-spacing] "2"))

(def content-changed-text-decoration
  (assoc-in content-base [:children 0 :children 0 :children 0 :text-decoration] "underline"))

(def content-changed-text-transform
  (assoc-in content-base [:children 0 :children 0 :children 0 :text-transform] "uppercase"))

(def content-changed-typography-ref-id
  (assoc-in content-base [:children 0 :children 0 :children 0 :typography-ref-id] "new-typography-id"))

(def content-changed-fills
  (assoc-in content-base [:children 0 :children 0 :children 0 :fills]
            [{:fill-color "#ff0000" :fill-opacity 1}]))


(t/deftest test-get-diff-type
  (let [diff-text                 (cttx/get-diff-type content-base content-changed-text)
        diff-attr                 (cttx/get-diff-type content-base content-changed-attr)
        diff-both                 (cttx/get-diff-type content-base content-changed-both)
        diff-structure            (cttx/get-diff-type content-base content-changed-structure)
        diff-structure-same-attrs (cttx/get-diff-type content-base content-changed-structure-same-attrs)

        ;; Numeric normalization: number 14 → string "14" → default → nil (same as base)
        diff-numeric-default      (cttx/get-diff-type content-base content-numeric-font-size-default)
        ;; Numeric normalization: number 32 → string "32" (different from base's "14")
        diff-numeric-different    (cttx/get-diff-type content-base content-numeric-font-size-32)
        ;; Numeric 32 vs string "32": should be equal after normalization
        diff-numeric-vs-string    (cttx/get-diff-type content-changed-attr content-numeric-font-size-32)

        ;; nil / empty-string / empty-list / default → all normalize to nil
        diff-nil-attr             (cttx/get-diff-type content-base content-nil-font-size)
        diff-empty-string         (cttx/get-diff-type content-base content-empty-string-font-size)
        diff-empty-list           (cttx/get-diff-type content-base content-empty-list-font-size)
        diff-default-value        (cttx/get-diff-type content-base content-default-font-size)

        ;; Non-text-node-attr key: should be ignored
        diff-non-text-node        (cttx/get-diff-type content-base content-non-text-node-attr)

        ;; Other text-node-attr categories
        diff-font-family          (cttx/get-diff-type content-base content-changed-font-family)
        diff-line-height          (cttx/get-diff-type content-base content-changed-line-height)
        diff-letter-spacing       (cttx/get-diff-type content-base content-changed-letter-spacing)
        diff-text-decoration      (cttx/get-diff-type content-base content-changed-text-decoration)
        diff-text-transform       (cttx/get-diff-type content-base content-changed-text-transform)
        diff-typography-ref       (cttx/get-diff-type content-base content-changed-typography-ref-id)
        diff-fills                (cttx/get-diff-type content-base content-changed-fills)]

    ;; Basic cases
    (t/is (= #{:text-content-text} diff-text))
    (t/is (= #{:text-content-attribute} diff-attr))
    (t/is (= #{:text-content-text :text-content-attribute} diff-both))
    (t/is (= #{:text-content-structure} diff-structure))
    (t/is (= #{:text-content-structure} diff-structure-same-attrs))

    ;; Numeric normalization
    (t/is (= #{} diff-numeric-default))
    (t/is (= #{:text-content-attribute} diff-numeric-different))
    (t/is (= #{} diff-numeric-vs-string))

    ;; nil / empty / default normalization (content-base has default font-size "14",
    ;; which normalizes to nil; nil also normalizes to nil → equal)
    (t/is (= #{} diff-nil-attr))
    (t/is (= #{} diff-empty-string))
    (t/is (= #{} diff-empty-list))
    (t/is (= #{} diff-default-value))

    ;; Non-text-node-attr key is ignored
    (t/is (= #{} diff-non-text-node))

    ;; Each text-node-attr category triggers attribute diff
    (t/is (= #{:text-content-attribute} diff-font-family))
    (t/is (= #{:text-content-attribute} diff-line-height))
    (t/is (= #{:text-content-attribute} diff-letter-spacing))
    (t/is (= #{:text-content-attribute} diff-text-decoration))
    (t/is (= #{:text-content-attribute} diff-text-transform))
    (t/is (= #{:text-content-attribute} diff-typography-ref))
    (t/is (= #{:text-content-attribute} diff-fills))))


(t/deftest test-get-diff-attrs
  (let [attrs-text                 (cttx/get-diff-attrs content-base content-changed-text)
        attrs-attr                 (cttx/get-diff-attrs content-base content-changed-attr)
        attrs-both                 (cttx/get-diff-attrs content-base content-changed-both)
        attrs-structure            (cttx/get-diff-attrs content-base content-changed-structure)
        attrs-structure-same-attrs (cttx/get-diff-attrs content-base content-changed-structure-same-attrs)

        ;; Numeric normalization: number 14 → string "14" → default → nil (same as base)
        attrs-numeric-default      (cttx/get-diff-attrs content-base content-numeric-font-size-default)
        ;; Numeric normalization: number 32 → string "32" (different from base's "14")
        attrs-numeric-different    (cttx/get-diff-attrs content-base content-numeric-font-size-32)
        ;; Numeric 32 vs string "32": should be equal after normalization
        attrs-numeric-vs-string    (cttx/get-diff-attrs content-changed-attr content-numeric-font-size-32)

        ;; nil / empty-string / empty-list / default → all normalize to nil
        attrs-nil-attr             (cttx/get-diff-attrs content-base content-nil-font-size)
        attrs-empty-string         (cttx/get-diff-attrs content-base content-empty-string-font-size)
        attrs-empty-list           (cttx/get-diff-attrs content-base content-empty-list-font-size)
        attrs-default-value        (cttx/get-diff-attrs content-base content-default-font-size)

        ;; Non-text-node-attr key: should be ignored
        attrs-non-text-node        (cttx/get-diff-attrs content-base content-non-text-node-attr)

        ;; Other text-node-attr categories
        attrs-font-family          (cttx/get-diff-attrs content-base content-changed-font-family)
        attrs-line-height          (cttx/get-diff-attrs content-base content-changed-line-height)
        attrs-letter-spacing       (cttx/get-diff-attrs content-base content-changed-letter-spacing)
        attrs-text-decoration      (cttx/get-diff-attrs content-base content-changed-text-decoration)
        attrs-text-transform       (cttx/get-diff-attrs content-base content-changed-text-transform)
        attrs-typography-ref       (cttx/get-diff-attrs content-base content-changed-typography-ref-id)
        attrs-fills                (cttx/get-diff-attrs content-base content-changed-fills)]

    ;; Basic cases
    (t/is (= #{} attrs-text))
    (t/is (= #{:font-size} attrs-attr))
    (t/is (= #{:font-size} attrs-both))
    (t/is (= #{} attrs-structure))
    (t/is (= #{} attrs-structure-same-attrs))

    ;; Numeric normalization
    (t/is (= #{} attrs-numeric-default))
    (t/is (= #{:font-size} attrs-numeric-different))
    (t/is (= #{} attrs-numeric-vs-string))

    ;; nil / empty / default normalization
    (t/is (= #{} attrs-nil-attr))
    (t/is (= #{} attrs-empty-string))
    (t/is (= #{} attrs-empty-list))
    (t/is (= #{} attrs-default-value))

    ;; Non-text-node-attr key is ignored
    (t/is (= #{} attrs-non-text-node))

    ;; Each text-node-attr category reports correct attr key
    (t/is (= #{:font-family} attrs-font-family))
    (t/is (= #{:line-height} attrs-line-height))
    (t/is (= #{:letter-spacing} attrs-letter-spacing))
    (t/is (= #{:text-decoration} attrs-text-decoration))
    (t/is (= #{:text-transform} attrs-text-transform))
    (t/is (= #{:typography-ref-id} attrs-typography-ref))
    (t/is (= #{:fills} attrs-fills))))


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
