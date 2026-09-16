;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.branch-merge-test
  "Engine-level tests for the three-way comparison, with data built by hand
  rather than through a file fixture.

  The case that matters here came from a real update-from-main: the branch
  copy and its base disagreed only on whether a reference attribute was
  present with a `nil` value or was absent, and because main had moved the
  same shapes, the engine called 368 of them `:modify-modify` conflicts."
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.uuid :as uuid]
   [clojure.test :refer [deftest is testing]]))

(def page-id (uuid/next))
(def shape-id (uuid/next))

(defn- file-data
  "A one-page file whose single text shape carries `extra` inside its
  `:content` (or omits it, when `extra` is empty) and sits at `y`."
  [y extra]
  {:pages [page-id]
   :pages-index
   {page-id
    {:id page-id
     :objects
     {shape-id
      {:id shape-id :name "label" :type :text :x 0 :y y :width 10 :height 10
       :content (merge {:type :root
                        :children [{:type :paragraph-set
                                    :children [{:type :paragraph
                                                :children [{:type :span :text "hi"}]}]}]}
                       extra)}}}}})

(def ^:private nil-ref {:typography-ref-file nil})

(deftest nil-ref-and-absent-ref-are-the-same-shape
  (testing "the branch's copy has the ref with nil, its base without, and main moved the shape"
    (let [res (bm/compute-merge (file-data 0 {})
                                (file-data 5 {})
                                (file-data 0 nil-ref)
                                :main->branch)]
      (is (zero? (count (:conflicts res))) "a nil-versus-absent reference is not a branch edit")
      (is (= 1 (count (:changes res))) "main's move still travels as a change")
      (is (= :modified (:status (first (:changes res)))))))

  (testing "and the same with the sides the other way round"
    (let [res (bm/compute-merge (file-data 0 nil-ref)
                                (file-data 5 nil-ref)
                                (file-data 0 {})
                                :main->branch)]
      (is (zero? (count (:conflicts res))))
      (is (= 1 (count (:changes res)))))))

(deftest a-real-difference-is-still-a-difference
  (testing "a value both sides moved off the base is still a conflict"
    (let [res (bm/compute-merge (file-data 0 {})
                                (file-data 5 {})
                                (file-data -5 {})
                                :main->branch)]
      (is (= 1 (count (:conflicts res))))
      (is (= :modify-modify (:reason (first (:conflicts res)))))))

  (testing "a change on one side only still travels"
    (let [res (bm/compute-merge (file-data 0 {})
                                (file-data 5 {})
                                (file-data 0 {})
                                :main->branch)]
      (is (zero? (count (:conflicts res))))
      (is (= 1 (count (:changes res)))))))
