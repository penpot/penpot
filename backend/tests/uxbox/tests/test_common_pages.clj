;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tests.test-common-pages
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [mockery.core :refer [with-mock]]
   [uxbox.common.pages :as cp]
   [uxbox.util.uuid :as uuid]
   [uxbox.tests.helpers :as th]))

(t/deftest process-change-add-obj-1
  (let [data cp/default-page-data
        id   (uuid/next)
        chg  {:type :add-obj
              :id id
              :frame-id uuid/zero
              :obj {:id id
                    :frame-id uuid/zero
                    :type :rect
                    :name "rect"}}
        res (cp/process-changes data [chg])]

    (t/is (= 2 (count (:objects res))))
    (t/is (= (:obj chg) (get-in res [:objects id])))
    (t/is (= [id] (get-in res [:objects uuid/zero :shapes])))))

(t/deftest process-change-mod-obj
  (let [data cp/default-page-data
        chg  {:type :mod-obj
              :id uuid/zero
              :operations [{:type :set
                            :attr :name
                            :val "foobar"}]}
        res (cp/process-changes data [chg])]
    (t/is (= "foobar" (get-in res [:objects uuid/zero :name])))))


(t/deftest process-change-del-obj-1
  (let [id   (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id])
                 (assoc-in [:objects id] {:id id
                                          :frame-id uuid/zero
                                          :type :rect
                                          :name "rect"}))
        chg  {:type :del-obj
              :id id}
        res (cp/process-changes data [chg])]

    (t/is (= 1 (count (:objects res))))
    (t/is (= [] (get-in res [:objects uuid/zero :shapes])))))

(t/deftest process-change-del-obj-2
  (let [id   (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id])
                 (assoc-in [:objects id] {:id id
                                          :frame-id uuid/zero
                                          :type :rect
                                          :name "rect"}))
        chg  {:type :del-obj
              :id uuid/zero}
        res (cp/process-changes data [chg])]
    (t/is (= 0 (count (:objects res))))))

(t/deftest process-change-mod-obj-abs-order
  (let [id1  (uuid/next)
        id2  (uuid/next)
        id3  (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id1 id2 id3]))]

    (t/testing "abs order 1"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :abs-order
                               :id id3
                               :index 0}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id3 id1 id2] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "abs order 2"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :abs-order
                               :id id1
                               :index 100}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id2 id3 id1] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "abs order 3"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :abs-order
                               :id id3
                               :index 1}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id1 id3 id2] (get-in res [:objects uuid/zero :shapes])))))
    ))


(t/deftest process-change-mod-obj-rel-order
  (let [id1  (uuid/next)
        id2  (uuid/next)
        id3  (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id1 id2 id3]))]

    (t/testing "rel order 1"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id3
                               :loc :down}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id1 id3 id2] (get-in res [:objects uuid/zero :shapes])))))


    (t/testing "rel order 2"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id1
                               :loc :top}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id2 id3 id1] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "rel order 3"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id2
                               :loc :up}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id1 id3 id2] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "rel order 4"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id3
                               :loc :bottom}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id3 id1 id2] (get-in res [:objects uuid/zero :shapes])))))
    ))

