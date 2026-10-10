;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.modal-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [cljs.test :as t :include-macros true]))

(t/deftest export-shapes-modal-event-carries-a-summary
  (let [shape-a (uuid/next)
        shape-b (uuid/next)
        exports [{:object-id shape-a :type :png}
                 {:object-id shape-a :type :svg}
                 {:object-id shape-b :type :png}]
        event   (modal/show :export-shapes
                            {:exports exports
                             :origin "workspace:sidebar"
                             :name "Page 1"})
        data    (ev/-data event)]

    (t/testing "the raw exports never leak into the event"
      (t/is (not (contains? data :exports))))

    (t/testing "the event carries counters instead"
      (t/is (= 3 (:num-exports data)))
      (t/is (= 2 (:num-shapes data)))
      (t/is (= 2 (:png data)))
      (t/is (= 1 (:svg data))))

    (t/testing "the modal context is preserved"
      (t/is (= "Page 1" (:page data)))
      (t/is (= :export-shapes (:name data))))

    (t/testing "origin is emitted as event metadata, not as a prop"
      (t/is (= "workspace:sidebar" (::ev/origin data)))
      (t/is (not (contains? data :origin))))))

(t/deftest other-modals-keep-their-props
  (let [event (modal/show :some-modal {:foo "bar" :items [1 2 3]})
        data  (ev/-data event)]
    (t/is (= "bar" (:foo data)))
    (t/is (= [1 2 3] (:items data)))
    (t/is (= :some-modal (:name data)))))

(t/deftest modal-origin-becomes-event-metadata
  (let [event (modal/show :some-modal {:foo "bar" :origin "dashboard"})
        data  (ev/-data event)]
    (t/is (= "dashboard" (::ev/origin data)))
    (t/is (not (contains? data :origin)))
    (t/is (= "bar" (:foo data)))))
