;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files-migrations-test
  (:require
   [app.common.data :as d]
   [app.common.files.migrations :as cfm]
   [app.common.pprint :as pp]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defmethod cfm/migrate-data "test/1" [data _] (update data :sum inc))
(defmethod cfm/migrate-data "test/2" [data _] (update data :sum inc))
(defmethod cfm/migrate-data "test/3" [data _] (update data :sum inc))

(t/deftest generic-migration-subsystem-1
  (let [migrations (into (d/ordered-set) ["test/1" "test/2" "test/3"])]
    (with-redefs [cfm/available-migrations migrations
                  ctf/check-file-data identity]
      (let [file  {:data {:sum 1}
                   :id 1
                   :migrations (d/ordered-set "test/1")}
            file' (cfm/migrate file nil)]
        (t/is (= cfm/available-migrations (:migrations file')))
        (t/is (= 3 (:sum (:data file'))))))))

(t/deftest migration-0024b-fix-stroke-cap-placement
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id {:id         shape-id
                               :type       :path
                               :stroke-cap-start "round"
                               :stroke-cap-end   "round"
                               :strokes    [{:stroke-color "#000000"
                                             :stroke-opacity 1
                                             :stroke-style :svg
                                             :stroke-width 2}
                                            {:stroke-color "#000000"
                                             :stroke-cap-start "round"
                                             :stroke-cap-end   "round"
                                             :stroke-opacity 1
                                             :stroke-style :svg
                                             :stroke-width 2}]}}}}}
        data'    (cfm/migrate-data data "0024b-fix-stroke-cap-placement")]

    (let [shape (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (nil? (:stroke-cap-start shape)) "top-level cap removed")
      (t/is (nil? (:stroke-cap-end shape)) "top-level cap removed")
      (t/is (= :round (get-in shape [:strokes 0 :stroke-cap-start])) "cap moved into stroke")
      (t/is (= :round (get-in shape [:strokes 0 :stroke-cap-end])) "cap moved into stroke")
      (t/is (= :round (get-in shape [:strokes 1 :stroke-cap-start])) "correct cap type")
      (t/is (= :round (get-in shape [:strokes 1 :stroke-cap-end])) "correct cap type"))))

(t/deftest migration-0024-fix-stroke-cap-no-strokes
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id {:id               shape-id
                               :type             :path
                               :stroke-cap-start :round
                               :stroke-cap-end   :round
                               :strokes          []}}}}}
        data'    (cfm/migrate-data data "0024b-fix-stroke-cap-placement")]

    (let [shape (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (nil? (:stroke-cap-start shape)) "top-level cap removed even with no strokes")
      (t/is (nil? (:stroke-cap-end shape)) "top-level cap removed even with no strokes"))))
