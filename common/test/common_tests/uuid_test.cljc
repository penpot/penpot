;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.uuid-test
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as props]))

(defspec non-repeating-uuid-next-1 100
  (props/for-all
   [uuid1 (s/gen ::us/uuid)
    uuid2 (s/gen ::us/uuid)]
   (t/is (not= uuid1 uuid2))))
