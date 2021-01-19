(ns app.test-library-sync
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [beicon.core :as rx]
            [app.test-helpers :as th]
            [app.common.data :as d]
            [app.common.uuid :as uuid]
            [app.common.pages.helpers :as cph]
            [app.main.data.workspace :as dw]
            [app.main.data.workspace.libraries :as dwl]
            [app.main.data.workspace.libraries-helpers :as dwlh]))

(t/deftest test-create-page
  (t/testing "create page"
    (let [state (-> th/initial-state
                    (th/sample-page))
          page  (th/current-page state)]
      (t/is (= (:name page) "page1")))))

(t/deftest test-create-shape
  (t/testing "create shape"
    (let [id    (uuid/next)
          state (-> th/initial-state
                    (th/sample-page)
                    (th/sample-shape :rect {:id id
                                         :name "Rect 1"}))
          page  (th/current-page state)
          shape (cph/get-shape page id)]
      (t/is (= (:name shape) "Rect 1")))))

(t/deftest synctest
  (t/testing "synctest"
    (let [state     {:workspace-local {:color-for-rename "something"}}
          new-state (->> state
                         (th/do-update
                           dwl/clear-color-for-rename))]
      (t/is (= (get-in new-state [:workspace-local :color-for-rename])
               nil)))))

(t/deftest asynctest
  (t/testing "asynctest"
    (t/async done
      (let [state {}
            color {:color "#ffffff"}]
        (->> state
             (th/do-watch-update
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
             (rx/subs done))))))

(t/deftest test-add-component
  (t/testing "Add a component"
    (t/async done
      (let [id1   (uuid/next)
            state (-> th/initial-state
                      (th/sample-page)
                      (th/sample-shape :rect
                                       {:id id1
                                        :name "Rect 1"}))]
        (->> state
             (th/do-update (dw/select-shape id1))
             (th/do-watch-update dwl/add-component) 
             (rx/map
               (fn [new-state]
                 (let [page  (th/current-page new-state)
                       shape (cph/get-shape page id1)
                       group (cph/get-shape page (:parent-id shape))

                       component (cph/get-component
                                   (:component-id group)
                                   (:current-file-id new-state)
                                   (dwlh/get-local-file new-state)
                                   nil)

                       c-shape (cph/get-shape
                                 component
                                 (:shape-ref shape))

                       c-group (cph/get-shape
                                 component
                                 (:shape-ref group))]

                   (t/is (= (:name shape) "Rect 1"))
                   (t/is (= (:name group) "Component-1"))
                   (t/is (= (:name component) "Component-1"))
                   (t/is (= (:name c-shape) "Rect 1"))
                   (t/is (= (:name c-group) "Component-1")))))

             (rx/subs done))))))

