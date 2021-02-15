(ns app.test-shapes
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [clojure.stacktrace :as stk]
            [beicon.core :as rx]
            [linked.core :as lks]
            [app.test-helpers.events :as the]
            [app.test-helpers.pages :as thp]
            [app.test-helpers.libraries :as thl]
            [app.common.geom.point :as gpt]
            [app.common.data :as d]
            [app.common.pages.helpers :as cph]
            [app.main.data.workspace.libraries :as dwl]))

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

(t/deftest synctest
  (t/testing "synctest"
    (let [state     {:workspace-local {:color-for-rename "something"}}
          new-state (->> state
                         (the/do-update
                           dwl/clear-color-for-rename))]
      (t/is (= (get-in new-state [:workspace-local :color-for-rename])
               nil)))))

(t/deftest asynctest
  (t/testing "asynctest"
    (t/async done
      (let [state {}
            color {:color "#ffffff"}]
        (->> state
             (the/do-watch-update
               (dwl/add-recent-color color))
             (rx/map
               (fn [new-state]
                 (t/is (= (get-in new-state [:workspace-file
                                             :data
                                             :recent-colors])
                          [color]))
                 (t/is (= (get-in new-state [:workspace-data
                                             :recent-colors])
                          [color]))))
             (rx/subs done done))))))

