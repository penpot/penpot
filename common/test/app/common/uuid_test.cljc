;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.uuid-test
  (:require
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [clojure.test.check.clojure-test :refer (defspec)]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as props]))

(def uuid-gen
  (->> gen/large-integer (gen/fmap (fn [_] (uuid/next)))))

(defspec non-repeating-uuid-next-1 100000
  (props/for-all
   [uuid1 uuid-gen
    uuid2 uuid-gen
    uuid3 uuid-gen
    uuid4 uuid-gen
    uuid5 uuid-gen]
   (t/is (not= uuid1 uuid2 uuid3 uuid4 uuid5))))


