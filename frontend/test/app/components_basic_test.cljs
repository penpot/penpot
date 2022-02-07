(ns app.components-basic-test
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.test-helpers.events :as the]
   [app.test-helpers.libraries :as thl]
   [app.test-helpers.pages :as thp]
   [beicon.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cljs.test :as t :include-macros true]
   [clojure.stacktrace :as stk]
   [linked.core :as lks]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

;; Test using potok
#_(t/deftest test-add-component-from-single-shape
    (t/testing "test-add-component-from-single-shape"
      (t/async
       done
       (let [state (-> thp/initial-state
                       (thp/sample-page)
                       (thp/sample-shape :shape1 :rect
                                         {:name "Rect 1"}))
             store (ptk/store {:state state})
             stream (ptk/input-stream store)
             end? (->> stream (rx/filter #(= ::end %)))]

         (->> stream
              (rx/take-until end?)
              (rx/last)
              (rx/do
                (fn []
                  (let [new-state @store
                        shape1 (thp/get-shape new-state :shape1)

                        [[group shape1] [c-group c-shape1] component]
                        (thl/resolve-instance-and-main
                         new-state
                         (:parent-id shape1))

                        file (dwlh/get-local-file new-state)]

                    (t/is (= (:name shape1) "Rect 1"))
                    (t/is (= (:name group) "Component-1"))
                    (t/is (= (:name component) "Component-1"))
                    (t/is (= (:name c-shape1) "Rect 1"))
                    (t/is (= (:name c-group) "Component-1"))

                    (thl/is-from-file group file))))

              (rx/subs done #(throw %)))

         (ptk/emit!
          store
          (dw/select-shape (thp/id :shape1))
          (dwl/add-component)
          ::end)))))

;; FAILING
#_(t/deftest test-add-component-from-single-shape
  (t/async
   done
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
                    (thl/resolve-instance-and-main
                     new-state
                     (:parent-id shape1))

                    file (dwlh/get-local-file new-state)]

                (t/is (= (:name shape1) "Rect 1"))
                (t/is (= (:name group) "Component-1"))
                (t/is (= (:name component) "Component-1"))
                (t/is (= (:name c-shape1) "Rect 1"))
                (t/is (= (:name c-group) "Component-1"))

                (thl/is-from-file group file))))

          (rx/subs done #(throw %))))))

;; FAILING
#_(t/deftest test-add-component-from-several-shapes
  (t/async
   done
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
                    (thl/resolve-instance-and-main
                     new-state
                     (:parent-id shape1))

                    file   (dwlh/get-local-file new-state)]

                ;; NOTE: the group name depends on having executed
                ;;       the previous test.
                (t/is (= (:name group) "Component-1"))
                (t/is (= (:name shape1) "Rect 1"))
                (t/is (= (:name shape2) "Rect 2"))
                (t/is (= (:name component) "Component-1"))
                (t/is (= (:name c-group) "Component-1"))
                (t/is (= (:name c-shape1) "Rect 1"))
                (t/is (= (:name c-shape2) "Rect 2"))

                (thl/is-from-file group file))))

          (rx/subs done #(throw %))))))


#_(t/deftest test-add-component-from-group
  (t/async
   done
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
                    (thl/resolve-instance-and-main
                     new-state
                     (thp/id :group1))

                    file   (dwlh/get-local-file new-state)]

                (t/is (= (:name shape1) "Rect 1"))
                (t/is (= (:name shape2) "Rect 2"))
                (t/is (= (:name group) "Group-1"))
                (t/is (= (:name component) "Group-1"))
                (t/is (= (:name c-shape1) "Rect 1"))
                (t/is (= (:name c-shape2) "Rect 2"))
                (t/is (= (:name c-group) "Group-1"))

                (thl/is-from-file group file))))

          (rx/subs done #(throw %))))))

(t/deftest test-rename-component
  (t/async
   done
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
              (let [libs      (dwlh/get-libraries new-state)
                    component (cph/get-component libs
                                                 (:component-file instance1)
                                                 (:component-id instance1))]
                (t/is (= (:name component)
                         "Renamed component")))))

          (rx/subs done #(throw %))))))

(t/deftest test-duplicate-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
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
                    (thl/resolve-instance-and-main
                     new-state
                     (:id instance1))

                    [[c-component2 c-shape2]
                     component2]
                    (thl/resolve-component
                     new-state
                     new-component-id)]

                (t/is (= (:name component2) "Rect-2")))))

          (rx/subs done #(throw %))))))

(t/deftest test-delete-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :instance1
                                       [(thp/id :shape1)]))

         instance1    (thp/get-shape state :instance1)
         component-id (:component-id instance1)]

     (->> state
          (the/do-watch-update (dwl/delete-component
                                {:id component-id}))
          (rx/do
            (fn [new-state]
              (let [[instance1 shape1]
                    (thl/resolve-instance
                     new-state
                     (:id instance1))

                    libs      (dwlh/get-libraries new-state)
                    component (cph/get-component libs
                                                 (:component-file instance1)
                                                 (:component-id instance1))]
                (t/is (nil? component)))))

          (rx/subs done #(throw %))))))

(t/deftest test-instantiate-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
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
              (let [new-instance-id (-> new-state
                                        wsh/lookup-selected
                                        first)

                    [[instance2 shape2]
                     [c-instance2 c-shape2]
                     component]
                    (thl/resolve-instance-and-main
                     new-state
                     new-instance-id)]

                (t/is (not= (:id instance1) (:id instance2)))
                (t/is (= (:id component) component-id))
                (t/is (= (:name instance2) "Rect-3"))
                (t/is (= (:name shape2) "Rect-1"))
                (t/is (= (:name c-instance2) "Rect-2"))
                (t/is (= (:name c-shape2) "Rect-1")))))

          (rx/subs done #(throw %))))))

(t/deftest test-detach-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :instance1
                                       [(thp/id :shape1)]))

         instance1    (thp/get-shape state :instance1)
         component-id (:component-id instance1)]

     (->> state
          (the/do-watch-update (dwl/detach-component
                                (:id instance1)))
          (rx/do
            (fn [new-state]
              (let [[instance1 shape1]
                    (thl/resolve-noninstance
                     new-state
                     (:id instance1))]

                (t/is (= (:name "Rect 1"))))))

          (rx/subs done #(throw %))))))

