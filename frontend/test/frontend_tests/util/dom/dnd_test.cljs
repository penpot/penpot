;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util.dom.dnd-test
  (:require
   [app.util.dom.dnd :as dnd]
   [cljs.test :as t :include-macros true]))

(t/deftest get-data-returns-nil-when-event-has-no-dataTransfer
  (t/testing "event without dataTransfer"
    (t/is (nil? (dnd/get-data #js {}))))
  (t/testing "event with explicit nil dataTransfer"
    (t/is (nil? (dnd/get-data #js {:dataTransfer nil}))))
  (t/testing "explicit data-type also returns nil for missing dataTransfer"
    (t/is (nil? (dnd/get-data #js {} "penpot/data")))))

(t/deftest get-data-reads-from-dataTransfer
  (t/testing "dataTransfer with matching key returns the value (non-decoded type)"
    (let [dt #js {:getData (fn [_type] "hello")}]
      (t/is (= "hello" (dnd/get-data #js {:dataTransfer dt} "text/plain"))))))
