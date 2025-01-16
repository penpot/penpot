(ns frontend-tests.test-helpers-shapes
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.main.data.workspace.libraries :as dwl]
   [app.test-helpers.events :as the]
   [app.test-helpers.libraries :as thl]
   [app.test-helpers.pages :as thp]
   [beicon.v2.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cljs.test :as t :include-macros true]
   [clojure.stacktrace :as stk]
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

(t/deftest asynctest
  (t/testing "asynctest"
    (t/async done
      (let [state {}
            color {:color clr/white}

            store (the/prepare-store state done
                                     (fn [new-state]
                                       (let [colors (:recent-colors new-state)]
                                         (t/is (= colors [color])))))]

        (ptk/emit!
         store
         (dwl/add-recent-color color)
         :the/end)))))

