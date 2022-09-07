(ns app.components-basic-test
  (:require
    [app.common.data :as d]
    [app.common.geom.point :as gpt]
    [app.common.pages.helpers :as cph]
    [app.common.types.container :as ctn]
    [app.common.types.file :as ctf]
    [app.main.data.workspace :as dw]
    [app.main.data.workspace.groups :as dwg]
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
    [linked.core :as lks]
    [potok.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(t/deftest test-add-component-from-single-shape
  (t/testing "test-add-component-from-single-shape"
    (t/async
      done
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect-1"}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ;; Uncomment to debug
                      ;; (ctf/dump-tree (get new-state :workspace-data)
                      ;;                (get new-state :current-page-id)
                      ;;                (get new-state :workspace-libraries))

                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect-2              #--> Rect-2
                      ;     Rect-1            ---> Rect-1
                      ;
                      ; [Rect-2]
                      ; Rect-2
                      ;   Rect-1
                      ;
                      (let [shape1 (thp/get-shape new-state :shape1)

                            [[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:parent-id shape1))

                            file (wsh/get-local-file new-state)]

                        (t/is (= (:name shape1) "Rect-1"))
                        (t/is (= (:name group) "Rect-2"))
                        (t/is (= (:name component) "Rect-2"))
                        (t/is (= (:name c-shape1) "Rect-1"))
                        (t/is (= (:name c-group) "Rect-2"))

                        (thl/is-from-file group file))))]

        (ptk/emit!
          store
          (dw/select-shape (thp/id :shape1))
          (dwl/add-component)
          :the/end)))))

;; Remove definitely when we ensure that the other method works
;; well in more advanced tests.
#_(t/deftest test-add-component-from-single-shape
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"}))]

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

                    file (wsh/get-local-file new-state)]

                (t/is (= (:name shape1) "Rect-1"))
                (t/is (= (:name group) "Component-1"))
                (t/is (= (:name component) "Component-1"))
                (t/is (= (:name c-shape1) "Rect-1"))
                (t/is (= (:name c-group) "Component-1"))

                (thl/is-from-file group file))))

          (rx/subs done #(throw %))))))

(t/deftest test-add-component-from-several-shapes
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect-2"}))

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Component-1         #--> Component-1
                   ;     Rect-1            ---> Rect-1
                   ;     Rect-2            ---> Rect-2
                   ;
                   ; [Component-1]
                   ; Component-1
                   ;   Rect-1
                   ;   Rect-2
                   ;
                   (let [shape1 (thp/get-shape new-state :shape1)

                         [[group shape1 shape2]
                          [c-group c-shape1 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                           new-state
                           (:parent-id shape1))

                         file   (wsh/get-local-file new-state)]

                     (t/is (= (:name group) "Component-1"))
                     (t/is (= (:name shape1) "Rect-1"))
                     (t/is (= (:name shape2) "Rect-2"))
                     (t/is (= (:name component) "Component-1"))
                     (t/is (= (:name c-group) "Component-1"))
                     (t/is (= (:name c-shape1) "Rect-1"))
                     (t/is (= (:name c-shape2) "Rect-2"))

                     (thl/is-from-file group file))))]

     (ptk/emit!
       store
       (dw/select-shapes (lks/set (thp/id :shape1)
                                  (thp/id :shape2)))
       (dwl/add-component)
       :the/end))))

(t/deftest test-add-component-from-group
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect-2"})
                   (thp/group-shapes :group1
                                     [(thp/id :shape1)
                                      (thp/id :shape2)]))

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Group-1             #--> Group-1
                   ;     Rect-1            ---> Rect-1
                   ;     Rect-2            ---> Rect-2
                   ;
                   ; [Group-1]
                   ; Group-1
                   ;   Rect-1
                   ;   Rect-2
                   ;
                   (let [[[group shape1 shape2]
                          [c-group c-shape1 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                           new-state
                           (thp/id :group1))

                         file   (wsh/get-local-file new-state)]

                     (t/is (= (:name shape1) "Rect-1"))
                     (t/is (= (:name shape2) "Rect-2"))
                     (t/is (= (:name group) "Group-1"))
                     (t/is (= (:name component) "Group-1"))
                     (t/is (= (:name c-shape1) "Rect-1"))
                     (t/is (= (:name c-shape2) "Rect-2"))
                     (t/is (= (:name c-group) "Group-1"))

                     (thl/is-from-file group file))))]

     (ptk/emit!
       store
       (dw/select-shape (thp/id :group1))
       (dwl/add-component)
       :the/end))))

(t/deftest test-add-component-from-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component1
                                       [(thp/id :shape1)]))

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-3              #--> Rect-3
                   ;     Rect-2            @--> Rect-2
                   ;       Rect-1          ---> Rect-1
                   ;
                   ; [Rect-2]
                   ; Rect-2
                   ;   Rect-1
                   ;
                   ; [Rect-2]
                   ; Rect-3
                   ;   Rect-2              @--> Rect-2
                   ;     Rect-1            ---> Rect-1
                   ;
                   (let [[[instance1 shape1]
                          [c-instance1 c-shape1]
                          component1]
                         (thl/resolve-instance-and-main
                           new-state
                           (thp/id :instance1)
                           true)

                         [[instance2 instance1' shape1']
                          [c-instance2 c-instance1' c-shape1']
                          component2]
                         (thl/resolve-instance-and-main
                           new-state
                           (:parent-id instance1))]

                     (t/is (= (:name shape1) "Rect-1"))
                     (t/is (= (:name instance1) "Rect-2"))
                     (t/is (= (:name component1) "Rect-2"))
                     (t/is (= (:name c-shape1) "Rect-1"))
                     (t/is (= (:name c-instance1) "Rect-2"))

                     (t/is (= (:name shape1') "Rect-1"))
                     (t/is (= (:name instance1') "Rect-2"))
                     (t/is (= (:name instance2) "Rect-3"))
                     (t/is (= (:name component2) "Rect-3"))
                     (t/is (= (:name c-shape1') "Rect-1"))
                     (t/is (= (:name c-instance1') "Rect-2"))
                     (t/is (= (:name c-instance2) "Rect-3")))))]

     (ptk/emit!
       store
       (dw/select-shape (thp/id :instance1))
       (dwl/add-component)
       :the/end))))

(t/deftest test-rename-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)]))

         instance1 (thp/get-shape state :instance1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-2              #--> Renamed component
                   ;     Rect-1            ---> Rect-1
                   ;
                   ; [Renamed]
                   ; Renamed component
                   ;   Rect-1
                   (let [libs      (wsh/get-libraries new-state)
                         component (ctf/get-component libs
                                                      (:component-file instance1)
                                                      (:component-id instance1))]
                     (t/is (= (:name component)
                              "Renamed component")))))]

     (ptk/emit!
       store
       (dwl/rename-component (:component-id instance1) "Renamed component")
       :the/end))))

(t/deftest test-duplicate-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)]))

         instance1    (thp/get-shape state :instance1)
         component-id (:component-id instance1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-2              #--> Rect-2
                   ;     Rect-1            ---> Rect-1
                   ;
                   ; [Rect-1]
                   ; Rect-2
                   ;   Rect-1
                   ;
                   ; [Rect-3]
                   ; Rect-2
                   ;   Rect-1
                   ;
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

                     (t/is (= (:name component2) "Rect-3")))))]

     (ptk/emit!
       store
       (dwl/duplicate-component {:id component-id})
       :the/end))))

(t/deftest test-delete-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)]))

         file (wsh/get-local-file state)

         instance1    (thp/get-shape state :instance1)
         component-id (:component-id instance1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;  Rect-2
                   ;    Rect-1
                   ;
                   (let [[instance1 shape1]
                         (thl/resolve-noninstance
                           new-state
                           (:id instance1))

                         libs      (wsh/get-libraries new-state)
                         component (ctf/get-component libs
                                                      (:component-file instance1)
                                                      (:component-id instance1))]

                     (t/is (some? instance1))
                     (t/is (some? shape1))
                     (t/is (nil? component)))))]

     (ptk/emit!
       store
       (dwl/delete-component {:id component-id})
       (dwl/sync-file (:id file) (:id file) :components component-id)
       :the/end))))

(t/deftest test-instantiate-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)]))

         file         (wsh/get-local-file state)
         component-id (thp/id :component-1)
         instance1    (thp/get-shape state :instance1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-2              #--> Rect-2
                   ;     Rect-1            ---> Rect-1
                   ;   Rect-3              #--> Rect-2
                   ;     Rect-1            ---> Rect-1
                   ;
                   ; [Rect-2]
                   ; Rect-2
                   ;   Rect-1
                   ;
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
                     (t/is (= (:name c-shape2) "Rect-1"))
                     (t/is (= (:component-file instance2)
                              thp/current-file-id)))))]

        (ptk/emit!
          store
          (dwl/instantiate-component (:id file)
                                     component-id
                                     (gpt/point 100 100))
          :the/end))))

(t/deftest test-instantiate-component-from-lib
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)])
                   (thp/move-to-library :lib1 "Library 1")
                   (thp/sample-page))

         library-id   (thp/id :lib1)
         component-id (thp/id :component-1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-2              #--> <Library 1> Rect-2
                   ;     Rect-1            ---> <Library 1> Rect-1
                   ;
                   (let [new-instance-id (-> new-state
                                             wsh/lookup-selected
                                             first)

                         [[instance2 shape2]
                          [c-instance2 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                           new-state
                           new-instance-id)]

                     (t/is (= (:id component) component-id))
                     (t/is (= (:name instance2) "Rect-2"))
                     (t/is (= (:name shape2) "Rect-1"))
                     (t/is (= (:name c-instance2) "Rect-2"))
                     (t/is (= (:name c-shape2) "Rect-1"))
                     (t/is (= (:component-file instance2) library-id)))))]

        (ptk/emit!
          store
          (dwl/instantiate-component library-id
                                     component-id
                                     (gpt/point 100 100))
          :the/end))))

(t/deftest test-detach-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)]))

         instance1    (thp/get-shape state :instance1)
         component-id (:component-id instance1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-2
                   ;     Rect-1
                   ;
                   ; [Rect-2]
                   ; Rect-2
                   ;   Rect-1
                   ;
                   (let [[instance1 shape1]
                         (thl/resolve-noninstance
                           new-state
                           (:id instance1))]

                     (t/is (some? instance1))
                     (t/is (some? shape1)))))]

        (ptk/emit!
          store
          (dwl/detach-component (:id instance1))
          :the/end))))

(t/deftest test-add-nested-component
  (t/async
    done
    (let [state (-> thp/initial-state
                    (thp/sample-page)
                    (thp/sample-shape :shape1 :rect
                                      {:name "Rect-1"}))

          file         (wsh/get-local-file state)
          instance1    (thp/get-shape state :instance1)
          component-id (:component-id instance1)

          store (the/prepare-store state done
                  (fn [new-state]
                    ; Expected shape tree:
                    ;
                    ; [Page]
                    ; Root Frame
                    ;   Group-1             #--> Group-1
                    ;     Rect-2            @--> Rect-2
                    ;       Rect-1          ---> Rect-1
                    ;
                    ; [Rect-1]
                    ; Rect-2
                    ;   Rect-1
                    ;
                    ; [Group-1]
                    ; Group-1
                    ;   Rect-2              @--> Rect-2
                    ;     Rect-1            ---> Rect-1
                    ;
                    (let [page    (thp/current-page new-state)
                          shape1  (thp/get-shape new-state :shape1)
                          parent1 (ctn/get-shape page (:parent-id shape1))

                          [[group shape1 shape2]
                           [c-group c-shape1 c-shape2]
                           component]
                          (thl/resolve-instance-and-main
                            new-state
                            (:parent-id parent1))]

                      (t/is (= (:name group) "Group-1"))
                      (t/is (= (:name shape1) "Rect-2"))
                      (t/is (= (:name shape2) "Rect-1"))
                      (t/is (= (:name component) "Group-1"))
                      (t/is (= (:name c-group) "Group-1"))
                      (t/is (= (:name c-shape1) "Rect-2"))
                      (t/is (= (:name c-shape2) "Rect-1")))))]

      (ptk/emit!
        store
        (dw/select-shape (thp/id :shape1))
        (dwl/add-component)
        dwg/group-selected
        (dwl/add-component)
        :the/end))))

(t/deftest test-instantiate-nested-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)])
                   (thp/group-shapes :group1
                                     [(thp/id :instance1)])
                   (thp/make-component :instance2 :component-2
                                       [(thp/id :group1)]))

         file         (wsh/get-local-file state)
         instance1    (thp/get-shape state :instance1)
         instance2    (thp/get-shape state :instance2)
         component-id (:component-id instance2)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect-2              #--> Rect-2
                   ;     Rect-2            @--> Rect-2
                   ;       Rect-1          ---> Rect-1
                   ;   Rect-3              #--> Rect-2
                   ;     Rect-2            @--> Rect-2
                   ;       Rect-1          ---> Rect-1
                   ;
                   ; [Rect-1]
                   ; Rect-2
                   ;   Rect-1
                   ;
                   ; [Rect-2]
                   ; Rect-2
                   ;   Rect-2              @--> Rect-2
                   ;     Rect-1            ---> Rect-1
                   ;
                   (let [new-instance-id (-> new-state
                                             wsh/lookup-selected
                                             first)

                         [[instance3 shape3 shape4]
                          [c-instance3 c-shape3 c-shape4]
                          component]
                         (thl/resolve-instance-and-main
                           new-state
                           new-instance-id)]

                     ; TODO: get and check the instance inside component [Rect-2]

                     (t/is (not= (:id instance1) (:id instance3)))
                     (t/is (= (:id component) component-id))
                     (t/is (= (:name instance3) "Rect-3"))
                     (t/is (= (:name shape3) "Rect-2"))
                     (t/is (= (:name shape4) "Rect-1"))
                     (t/is (= (:name c-instance3) "Rect-2"))
                     (t/is (= (:name c-shape3) "Rect-2"))
                     (t/is (= (:name c-shape4) "Rect-1")))))]

        (ptk/emit!
          store
          (dwl/instantiate-component (:id file)
                                     (:component-id instance2)
                                     (gpt/point 100 100))
          :the/end))))

(t/deftest test-instantiate-nested-component-from-lib
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect-1"})
                   (thp/make-component :instance1 :component-1
                                       [(thp/id :shape1)])
                   (thp/move-to-library :lib1 "Library 1")
                   (thp/sample-page)
                   (thp/instantiate-component :instance2
                                              (thp/id :component-1)
                                              (thp/id :lib1)))

         library-id   (thp/id :lib1)
         component-id (thp/id :component-1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Group-1             #--> Group-1
                   ;     Rect-2            @--> <Library 1> Rect-2
                   ;       Rect-1          ---> <Library 1> Rect-1
                   ;
                   ; [Group-1]
                   ; Group-1
                   ;   Rect-2              @--> <Library 1> Rect-2
                   ;     Rect-1            ---> <Library 1> Rect-1
                   ;
                   (let [instance2 (thp/get-shape new-state :instance2)

                         [[group1 shape1 shape2] [c-group1 c-shape1 c-shape2] component]
                         (thl/resolve-instance-and-main
                           new-state
                           (:parent-id instance2))]

                     (t/is (= (:name group1) "Group-1"))
                     (t/is (= (:name shape1) "Rect-2"))
                     (t/is (= (:name shape2) "Rect-1"))
                     (t/is (= (:name c-group1) "Group-1"))
                     (t/is (= (:name c-shape1) "Rect-2"))
                     (t/is (= (:name c-shape2) "Rect-1"))
                     (t/is (= (:component-file group1) thp/current-file-id))
                     (t/is (= (:component-file shape1) library-id))
                     (t/is (= (:component-file shape2) nil))
                     (t/is (= (:component-file c-group1) nil))
                     (t/is (= (:component-file c-shape1) library-id))
                     (t/is (= (:component-file c-shape2) nil)))))]

        (ptk/emit!
          store
          (dw/select-shape (thp/id :instance2))
          dwg/group-selected
          (dwl/add-component)
          :the/end))))

