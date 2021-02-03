(ns app.test-library-sync
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
            [app.main.data.workspace :as dw]
            [app.main.data.workspace.libraries :as dwl]
            [app.main.data.workspace.libraries-helpers :as dwlh]))

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

(t/deftest test-add-component-from-single-shape
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"}))]

        (->> state
             (the/do-update (dw/select-shape (thp/id :shape1)))
             (the/do-watch-update dwl/add-component) 
             (rx/do
               (fn [new-state]
                 (let [shape1 (thp/get-shape new-state :shape1)

                       [[group shape1] [c-group c-shape1] component]
                       (thl/resolve-instance-and-master
                         new-state
                         (:parent-id shape1))

                       file (dwlh/get-local-file new-state)]

                   (t/is (= (:name shape1) "Rect 1"))
                   (t/is (= (:name group) "Component-1"))
                   (t/is (= (:name component) "Component-1"))
                   (t/is (= (:name c-shape1) "Rect 1"))
                   (t/is (= (:name c-group) "Component-1"))

                   (thl/is-from-file group file))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

        (catch :default e
          (println (.-stack e))
          (done)))))

(t/deftest test-add-component-from-several-shapes
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"}))]

        (->> state
             (the/do-update (dw/select-shapes (lks/set
                                                (thp/id :shape1)
                                                (thp/id :shape2))))
             (the/do-watch-update dwl/add-component) 
             (rx/do
               (fn [new-state]
                 (let [shape1 (thp/get-shape new-state :shape1)

                       [[group shape1 shape2]
                        [c-group c-shape1 c-shape2]
                        component]
                       (thl/resolve-instance-and-master
                         new-state
                         (:parent-id shape1))

                       file   (dwlh/get-local-file new-state)]

                   ;; NOTE: the group name depends on having executed
                   ;;       the previous test.
                   (t/is (= (:name group) "Component-2"))
                   (t/is (= (:name shape1) "Rect 1"))
                   (t/is (= (:name shape2) "Rect 2"))
                   (t/is (= (:name component) "Component-2"))
                   (t/is (= (:name c-group) "Component-2"))
                   (t/is (= (:name c-shape1) "Rect 1"))
                   (t/is (= (:name c-shape2) "Rect 2"))

                   (thl/is-from-file group file))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

        (catch :default e
          (println (.-stack e))
          (done)))))


(t/deftest test-add-component-from-group
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/group-shapes :group1
                                        [(thp/id :shape1)
                                         (thp/id :shape2)]))]

        (->> state
             (the/do-update (dw/select-shape (thp/id :group1)))
             (the/do-watch-update dwl/add-component) 
             (rx/do
               (fn [new-state]
                 (let [[[group shape1 shape2]
                        [c-group c-shape1 c-shape2]
                        component]
                       (thl/resolve-instance-and-master
                         new-state
                         (thp/id :group1))

                       file   (dwlh/get-local-file new-state)]

                   (t/is (= (:name shape1) "Rect 1"))
                   (t/is (= (:name shape2) "Rect 2"))
                   (t/is (= (:name group) "Group-3"))
                   (t/is (= (:name component) "Group-3"))
                   (t/is (= (:name c-shape1) "Rect 1"))
                   (t/is (= (:name c-shape2) "Rect 2"))
                   (t/is (= (:name c-group) "Group-3"))

                   (thl/is-from-file group file))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

      (catch :default e
        (println (.-stack e))
        (done)))))

(t/deftest test-rename-component
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/make-component :instance1
                                          [(thp/id :shape1)]))

        instance1 (thp/get-shape state :instance1)]

        (->> state
             (the/do-watch-update (dwl/rename-component
                                    (:component-id instance1)
                                    "Renamed component"))
             (rx/do
               (fn [new-state]
                 (let [file      (dwlh/get-local-file new-state)
                       component (cph/get-component
                                   (:component-id instance1)
                                   (:component-file instance1)
                                   file
                                   {})]

                   (t/is (= (:name component)
                            "Renamed component")))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

      (catch :default e
        (println (.-stack e))
        (done)))))

(t/deftest test-duplicate-component
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/make-component :instance1
                                          [(thp/id :shape1)]))

            instance1    (thp/get-shape state :instance1)
            component-id (:component-id instance1)]

        (->> state
             (the/do-watch-update (dwl/duplicate-component
                                    {:id component-id}))
             (rx/do
               (fn [new-state]
                 (let [new-component-id (->> (get-in new-state
                                                     [:workspace-data
                                                      :components])
                                             (keys)
                                             (filter #(not= % component-id))
                                             (first))

                       [[instance1 shape1]
                        [c-instance1 c-shape1]
                        component1]
                       (thl/resolve-instance-and-master
                         new-state
                         (:id instance1))

                       [[c-component2 c-shape2]
                        component2]
                       (thl/resolve-component
                         new-state
                         new-component-id)]

                   (t/is (= (:name component2)
                            "Component-6")))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

      (catch :default e
        (println (.-stack e))
        (done)))))

(t/deftest test-instantiate-component
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/make-component :instance1
                                          [(thp/id :shape1)]))

        file         (dwlh/get-local-file state)
        instance1    (thp/get-shape state :instance1)
        component-id (:component-id instance1)]

        (->> state
             (the/do-watch-update (dwl/instantiate-component
                                    (:id file)
                                    (:component-id instance1)
                                    (gpt/point 100 100)))
             (rx/do
               (fn [new-state]
                 (let [new-instance-id (-> (get-in new-state
                                                   [:workspace-local :selected])
                                           first)

                       [[instance2 shape2]
                        [c-instance2 c-shape2]
                        component]
                       (thl/resolve-instance-and-master
                         new-state
                         new-instance-id)]

                   (t/is (not= (:id instance1) (:id instance2)))
                   (t/is (= (:id component) component-id))
                   (t/is (= (:name instance2) "Component-7"))
                   (t/is (= (:name shape2) "Rect 1"))
                   (t/is (= (:name c-instance2) "Component-6"))
                   (t/is (= (:name c-shape2) "Rect 1")))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

      (catch :default e
        (println (.-stack e))
        (done)))))

