;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files-migrations-test
  (:require
   [app.common.data :as d]
   [app.common.files.migrations :as cfm]
   [app.common.pprint :as pp]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest test-generic-migration-subsystem-1
  (let [migrations [{:id 1 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 2 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 3 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 4 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 5 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 6 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 7 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 8 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 9 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 10 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 11 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 12 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}
                    {:id 13 :migrate-up (comp inc inc) :migrate-down (comp dec dec)}]]

    (t/testing "migrate up 1"
      (let [result (cfm/migrate-data 0 migrations 0 2)]
        (t/is (= result 4))))

    (t/testing "migrate up 2"
      (let [result (cfm/migrate-data 0 migrations 0 20)]
        (t/is (= result 26))))

    (t/testing "migrate down 1"
      (let [result (cfm/migrate-data 12 migrations 6 3)]
        (t/is (= result 6))))

    (t/testing "migrate down 2"
      (let [result (cfm/migrate-data 12 migrations 6 0)]
        (t/is (= result 0))))))

(t/deftest test-migration-8-1
  (let [page-id (uuid/custom 0 0)
        objects [{:type :rect :id (uuid/custom 1 0)}
                 {:type :group
                  :id (uuid/custom 1 1)
                  :selrect {}
                  :shapes [(uuid/custom 1 2) (uuid/custom 1 0)]}
                 {:type :group
                  :id (uuid/custom 1 2)
                  :selrect {}
                  :shapes [(uuid/custom 1 3)]}
                 {:type :group
                  :id (uuid/custom 1 3)
                  :selrect {}
                  :shapes [(uuid/custom 1 4)]}
                 {:type :group
                  :id (uuid/custom 1 4)
                  :selrect {}
                  :shapes [(uuid/custom 1 5)]}
                 {:type :path :id (uuid/custom 1 5)}]

        data    {:pages-index {page-id {:objects (d/index-by :id objects)}}
                 :components {}}

        res     (cfm/migrate-data data cfm/migrations 7 8)]

    (t/is (= data res))))

(t/deftest test-migration-8-2
  (let [page-id (uuid/custom 0 0)
        objects [{:type :rect :id (uuid/custom 1 0)}
                 {:type :group
                  :id (uuid/custom 1 1)
                  :selrect {}
                  :shapes [(uuid/custom 1 2) (uuid/custom 1 0)]}
                 {:type :group
                  :id (uuid/custom 1 2)
                  :selrect {}
                  :shapes [(uuid/custom 1 3)]}
                 {:type :group
                  :id (uuid/custom 1 3)
                  :selrect {}
                  :shapes [(uuid/custom 1 4)]}
                 {:type :group
                  :id (uuid/custom 1 4)
                  :selrect {}
                  :shapes []}
                 {:type :path :id (uuid/custom 1 5)}]

        data    {:pages-index {page-id {:objects (d/index-by :id objects)}}
                 :components {}}

        expect   (-> data
                     (update-in [:pages-index page-id :objects] dissoc
                                (uuid/custom 1 2)
                                (uuid/custom 1 3)
                                (uuid/custom 1 4))
                     (update-in [:pages-index page-id :objects (uuid/custom 1 1) :shapes]
                                (fn [shapes]
                                  (let [id (uuid/custom 1 2)]
                                    (into [] (remove #(= id %)) shapes)))))

        res     (cfm/migrate-data data cfm/migrations 7 8)]

    ;; (pprint res)
    ;; (pprint expect)

    (t/is (= expect res))))
