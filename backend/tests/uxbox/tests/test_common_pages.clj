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

(t/deftest process-change-add-shape
  (let [data cp/default-page-data
        id   (uuid/next)
        chg  {:type :add-shape
              :id id
              :session-id (uuid/next)
              :shape {:id id
                      :type :rect
                      :name "rect"}}
        res (cp/process-changes data [chg])]

    (t/is (= 1 (count (:shapes res))))
    (t/is (= 0 (count (:canvas res))))

    (t/is (= id (get-in res [:shapes 0])))
    (t/is (= (:shape chg)
             (get-in res [:shapes-by-id id])))))

(t/deftest process-change-add-canvas
  (let [data cp/default-page-data
        id   (uuid/next)
        chg  {:type :add-canvas
              :id id
              :session-id (uuid/next)
              :shape {:id id
                      :type :rect
                      :name "rect"}}
        res (cp/process-changes data [chg])]
    (t/is (= 0 (count (:shapes res))))
    (t/is (= 1 (count (:canvas res))))

    (t/is (= id (get-in res [:canvas 0])))
    (t/is (= (:shape chg)
             (get-in res [:shapes-by-id id])))))


(t/deftest process-change-mod-shape
  (let [id   (uuid/next)
        data (merge cp/default-page-data
                    {:shapes [id]
                     :shapes-by-id {id {:id id
                                        :type :rect
                                        :name "rect"}}})

        chg  {:type :mod-shape
              :id id
              :session-id (uuid/next)
              :operations [[:set :name "foobar"]]}
        res (cp/process-changes data [chg])]

    (t/is (= 1 (count (:shapes res))))
    (t/is (= 0 (count (:canvas res))))
    (t/is (= "foobar"
             (get-in res [:shapes-by-id id :name])))))

(t/deftest process-change-mod-opts
  (t/testing "mod-opts add"
    (let [data cp/default-page-data
          chg  {:type :mod-opts
                :session-id (uuid/next)
                :operations [[:set :foo "bar"]]}
          res (cp/process-changes data [chg])]

      (t/is (= 0 (count (:shapes res))))
      (t/is (= 0 (count (:canvas res))))
      (t/is (empty? (:shapes-by-id res)))
      (t/is (= "bar" (get-in res [:options :foo])))))

  (t/testing "mod-opts set nil"
    (let [data (merge cp/default-page-data
                      {:options {:foo "bar"}})
          chg  {:type :mod-opts
                :session-id (uuid/next)
                :operations [[:set :foo nil]]}
          res (cp/process-changes data [chg])]

      (t/is (= 0 (count (:shapes res))))
      (t/is (= 0 (count (:canvas res))))
      (t/is (empty? (:shapes-by-id res)))
      (t/is (not (contains? (:options res) :foo)))))
  )


(t/deftest process-change-del-shape
  (let [id   (uuid/next)
        data (merge cp/default-page-data
                    {:shapes [id]
                     :shapes-by-id {id {:id id
                                        :type :rect
                                        :name "rect"}}})
        chg  {:type :del-shape
              :id id
              :session-id (uuid/next)}
        res (cp/process-changes data [chg])]

    (t/is (= 0 (count (:shapes res))))
    (t/is (= 0 (count (:canvas res))))
    (t/is (empty? (:shapes-by-id res)))))

(t/deftest process-change-del-canvas
  (let [id   (uuid/next)
        data (merge cp/default-page-data
                    {:canvas [id]
                     :shapes-by-id {id {:id id
                                        :type :canvas
                                        :name "rect"}}})
        chg  {:type :del-canvas
              :id id
              :session-id (uuid/next)}
        res (cp/process-changes data [chg])]

    (t/is (= 0 (count (:shapes res))))
    (t/is (= 0 (count (:canvas res))))
    (t/is (empty? (:shapes-by-id res)))))


(t/deftest process-change-mov-shape
  (let [id1  (uuid/next)
        id2  (uuid/next)
        id3  (uuid/next)
        data (merge cp/default-page-data
                    {:shapes [id1 id2 id3]})]

    (t/testing "mov-canvas 1"
      (let [chg {:type :mov-shape
                 :id id3
                 :index 0
                 :session-id (uuid/next)}
            res (cp/process-changes data [chg])]
        (t/is (= [id3 id1 id2] (:shapes res)))))

    (t/testing "mov-canvas 2"
      (let [chg {:type :mov-shape
                 :id id3
                 :index 100
                 :session-id (uuid/next)}
            res (cp/process-changes data [chg])]
        (t/is (= [id1 id2 id3] (:shapes res)))))

    (t/testing "mov-canvas 3"
      (let [chg {:type :mov-shape
                 :id id3
                 :index 1
                 :session-id (uuid/next)}
            res (cp/process-changes data [chg])]
        (t/is (= [id1 id3 id2] (:shapes res)))))
    ))

