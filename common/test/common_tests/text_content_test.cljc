;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.text-content-test
  (:require
   [app.common.types.text-content :as cttc]
   [clojure.test :as t :include-macros true]))

(def content-base
  {:type "root",
   :children
   [{:type "paragraph-set",
     :children
     [{:line-height "1.2",
       :font-style "normal",
       :children
       [{:line-height "1.2",
         :font-style "normal",
         :typography-ref-id nil,
         :text-transform "none",
         :text-align "left",
         :font-id "sourcesanspro",
         :font-size "24",
         :font-weight "400",
         :typography-ref-file nil,
         :text-direction "ltr",
         :font-variant-id "regular",
         :text-decoration "none",
         :letter-spacing "0",
         :fills [{:fill-color "#000000", :fill-opacity 1}],
         :font-family "sourcesanspro",
         :text "one "}
        {:line-height "1.2",
         :font-style "normal",
         :typography-ref-id nil,
         :text-transform "none",
         :text-align "left",
         :font-id "sourcesanspro",
         :font-size "24",
         :font-weight "400",
         :typography-ref-file nil,
         :text-direction "ltr",
         :font-variant-id "regular",
         :text-decoration "none",
         :letter-spacing "0",
         :fills [{:fill-color "#e5d9d9", :fill-opacity 1}],
         :font-family "sourcesanspro",
         :text "two"}
        {:line-height "1.2",
         :font-style "normal",
         :typography-ref-id nil,
         :text-transform "none",
         :text-align "left",
         :font-id "sourcesanspro",
         :font-size "24",
         :font-weight "400",
         :typography-ref-file nil,
         :text-direction "ltr",
         :font-variant-id "regular",
         :text-decoration "none",
         :letter-spacing "0",
         :fills [{:fill-color "#000000", :fill-opacity 1}],
         :font-family "sourcesanspro",
         :text " "}
        {:line-height "1.2",
         :font-style "normal",
         :typography-ref-id nil,
         :text-transform "none",
         :text-align "left",
         :font-id "sourcesanspro",
         :font-size "24",
         :font-weight "400",
         :typography-ref-file nil,
         :text-direction "ltr",
         :font-variant-id "regular",
         :text-decoration "none",
         :letter-spacing "0",
         :fills [{:fill-color "#311de7", :fill-opacity 1}],
         :font-family "sourcesanspro",
         :text "three"}],
       :typography-ref-id nil,
       :text-transform "none",
       :text-align "left",
       :font-id "sourcesanspro",
       :font-size "24",
       :font-weight "400",
       :typography-ref-file nil,
       :text-direction "ltr",
       :type "paragraph",
       :font-variant-id "regular",
       :text-decoration "none",
       :letter-spacing "0",
       :fills [{:fill-color "#000000", :fill-opacity 1}],
       :font-family "sourcesanspro"}]}]})

(def content-changed-text (assoc-in content-base [:children 0 :children 0 :children 1 :text] "changed"))
(def content-changed-attr (assoc-in content-base [:children 0 :children 0 :children 1 :font-size] "32"))
(def content-changed-both (-> content-base
                              (assoc-in [:children 0 :children 0 :children 1 :text] "changed")
                              (assoc-in  [:children 0 :children 0 :children 1 :font-size] "32")))

(def content-changed-structure (update-in content-base [:children 0 :children 0 :children]
                                          #(vec (concat (subvec % 0 1) (subvec % 2)))))


(t/deftest test-text-content-diff-text
  (let [diff-text (cttc/text-content-diff content-base content-changed-text)
        diff-attr (cttc/text-content-diff content-base content-changed-attr)
        diff-both (cttc/text-content-diff content-base content-changed-both)
        diff-structure (cttc/text-content-diff content-base content-changed-structure)]
    (t/is (= diff-text #{:text}))
    (t/is (= diff-attr #{:attribute}))
    (t/is (= diff-both #{:text :attribute}))
    (t/is (= diff-structure #{:structure}))))


(t/deftest test-text-content-diff-text
  (t/is (true? (cttc/equal-structure? content-base content-changed-text)))
  (t/is (true? (cttc/equal-structure? content-base content-changed-attr)))
  (t/is (true? (cttc/equal-structure? content-base content-changed-both)))
  (t/is (false? (cttc/equal-structure? content-base content-changed-structure))))


(t/deftest test-text-content-diff-text
  (let [copy-base-to-changed-text (cttc/copy-text-keys content-base content-changed-text)
        copy-changed-text-to-base (cttc/copy-text-keys content-changed-text content-base)

        copy-base-to-changed-attr (cttc/copy-text-keys content-base content-changed-attr)

        copy-changes-text-to-changed-attr (cttc/copy-text-keys content-changed-text content-changed-attr)
        updates-text-in-changed-attr (assoc-in content-changed-attr [:children 0 :children 0 :children 1 :text] "changed")]

    ;; If we copy the text of the base to the content-changed-text, the result is equal than the base
    (t/is (= copy-base-to-changed-text content-base))

    ;; If we copy the text of the content-changed-text to the base, the result is equal than the content-changed-text
    (t/is (= copy-changed-text-to-base content-changed-text))

    ;; If we copy the text of the base to the content-changed-attr, it doesn't nothing because the text were equal
    (t/is (= copy-base-to-changed-attr content-changed-attr))

    ;; If we copy the text of the content-changed-text to the content-changed-attr, it keeps the changes on the attrs
    ;; and the changes on the texts
    (t/is (= copy-changes-text-to-changed-attr updates-text-in-changed-attr))))