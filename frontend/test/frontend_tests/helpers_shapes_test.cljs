;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers-shapes-test
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.main.data.workspace.libraries :as dwl]
   [beicon.v2.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cljs.test :as t :include-macros true]
   [clojure.stacktrace :as stk]
   [frontend-tests.helpers.events :as the]
   [frontend-tests.helpers.libraries :as thl]
   [frontend-tests.helpers.pages :as thp]
   [linked.core :as lks]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(t/deftest test-create-page
  (t/testing "create page"
    (let [state (-> thp/initial-state
                    (thp/sample-page))
          page  (thp/current-page state)]
      (t/is (= (:name page) "page1")))))

(t/deftest test-create-shape
  (t/testing "create shape"
    (let [state (-> thp/initial-state
                    (thp/sample-page)
                    (thp/sample-shape :shape1 :rect
                                      {:name "Rect 1"}))
          shape (thp/get-shape state :shape1)]
      (t/is (= (:name shape) "Rect 1")))))
