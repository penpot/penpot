;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.text-editor-content-range-test
  "The styled content of a char range, as copied from the editor selection."
  (:require
   [app.render-wasm.text-editor :as text-editor]
   [cljs.test :as t :include-macros true]))

(defn- span [text attrs]
  (assoc attrs :text text))

(defn- paragraph [attrs & spans]
  (assoc attrs :type "paragraph" :children (vec spans)))

(defn- content [& paragraphs]
  {:type "root"
   :children [{:type "paragraph-set" :children (vec paragraphs)}]})

(def ^:private bold {:font-weight "700"})
(def ^:private red {:fills [{:fill-color "#ff0000" :fill-opacity 1}]})
(def ^:private centered {:text-align "center"})
(def ^:private right {:text-align "right"})

(def ^:private source
  (content (paragraph centered (span "Hello " {}) (span "bold" bold) (span " end" {}))
           (paragraph right (span "red" red) (span " tail" {}))))

(t/deftest single-paragraph
  (t/testing "spans are cut at both ends and keep their styles"
    (t/is (= (content (paragraph centered (span "lo " {}) (span "bold" bold) (span " e" {})))
             (text-editor/content-range source {:start-para 0 :start-offset 3
                                                :end-para 0 :end-offset 12})))))

(t/deftest across-paragraphs
  (t/testing "every paragraph in the range keeps its attrs"
    (t/is (= (content (paragraph centered (span "end" {}))
                      (paragraph right (span "re" red)))
             (text-editor/content-range source {:start-para 0 :start-offset 11
                                                :end-para 1 :end-offset 2}))))

  (t/testing "a range starting at a paragraph end keeps an empty paragraph with its style"
    (t/is (= (content (paragraph centered (span "" {}))
                      (paragraph right (span "red" red)))
             (text-editor/content-range source {:start-para 0 :start-offset 14
                                                :end-para 1 :end-offset 3})))))

(t/deftest utf16-offsets
  (t/testing "offsets count UTF-16 units like WASM's"
    (t/is (= (content (paragraph {} (span "😀b" bold)))
             (text-editor/content-range (content (paragraph {} (span "a😀b" bold)))
                                        {:start-para 0 :start-offset 1
                                         :end-para 0 :end-offset 4})))))
