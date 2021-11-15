;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages-migrations-test
  (:require
   [clojure.test :as t]
   [clojure.pprint :refer [pprint]]
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.pages.migrations :as cpm]
   [app.common.uuid :as uuid]))

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
                 :components {}
                 :version 7}

        res     (cpm/migrate-data data)]

    ;; (pprint data)
    ;; (pprint res)

    (t/is (= (dissoc data :version)
             (dissoc res :version)))))

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
                 :components {}
                 :version 7}

        expect   (-> data
                    (update-in [:pages-index page-id :objects] dissoc
                               (uuid/custom 1 2)
                               (uuid/custom 1 3)
                               (uuid/custom 1 4))
                    (update-in [:pages-index page-id :objects (uuid/custom 1 1) :shapes]
                               (fn [shapes]
                                 (let [id (uuid/custom 1 2)]
                                   (into [] (remove #(= id %)) shapes)))))

        res     (cpm/migrate-data data)]

    ;; (pprint res)
    ;; (pprint expect)

    (t/is (= (dissoc expect :version)
             (dissoc res :version)))
    ))
