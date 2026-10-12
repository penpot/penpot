;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.components-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.component :as ctk]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-valid-touched-group
  (t/is (ctk/valid-touched-group? :name-group))
  (t/is (ctk/valid-touched-group? :geometry-group))
  (t/is (ctk/valid-touched-group? :swap-group))
  (t/is (not (ctk/valid-touched-group? :this-is-not-a-group)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f)))
  (t/is (not (ctk/valid-touched-group? nil))))
