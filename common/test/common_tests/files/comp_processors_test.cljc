;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.comp-processors-test
  (:require
  [app.common.files.comp-processors :as cfcp]
;;    [app.common.test-helpers.components :as thc]
;;    [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
;;    [app.common.test-helpers.ids-map :as thi]
;;    [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/deftest test-fix-missing-swap-slots
  (t/testing "empty file should not need any action"
    (let [file (thf/sample-file :file1)]
      (t/is (= file (cfcp/fix-missing-swap-slots file))))))
