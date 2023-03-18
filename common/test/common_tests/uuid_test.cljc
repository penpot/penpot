;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.uuid-test
  (:require
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [clojure.test :as t]))

(t/deftest non-repeating-uuid-next-1-schema
  (sg/check!
   (sg/for [uuid1 (sg/generator ::sm/uuid)
            uuid2 (sg/generator ::sm/uuid)]
     (t/is (not= uuid1 uuid2)))
   {:num 100}))
