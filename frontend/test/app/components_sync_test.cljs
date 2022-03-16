(ns app.components-sync-test
  (:require
    [app.common.colors :as clr]
    [app.common.data :as d]
    [app.common.geom.point :as gpt]
    [app.common.pages.helpers :as cph]
    [app.main.data.workspace :as dw]
    [app.main.data.workspace.changes :as dch]
    [app.main.data.workspace.common :as dwc]
    [app.main.data.workspace.libraries :as dwl]
    [app.main.data.workspace.libraries-helpers :as dwlh]
    [app.main.data.workspace.state-helpers :as wsh]
    [app.test-helpers.events :as the]
    [app.test-helpers.libraries :as thl]
    [app.test-helpers.pages :as thp]
    [beicon.core :as rx]
    [cljs.pprint :refer [pprint]]
    [cljs.test :as t :include-macros true]
    [linked.core :as lks]
    [potok.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(t/deftest test-touched
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)]))

            shape1    (thp/get-shape state :shape1)

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1*           ---> Rect 1
                      ;         #{:fill-group}
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)
                            shape1 (thp/get-shape new-state :shape1)

                            [[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:touched shape1) #{:fill-group}))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/white))
                        (t/is (= (:fill-opacity c-shape1) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          :the/end)))))

(t/deftest test-touched-children-add
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)])
                      (thp/sample-shape :shape2 :circle
                                        {:name "Circle 1"}))

            instance1 (thp/get-shape state :instance1)
            shape2    (thp/get-shape state :shape2)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1*           #--> Rect 1-1
                      ;       #{:shapes-group}
                      ;     Circle 1
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1 shape2] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main-allow-dangling
                              new-state
                              (:id instance1))]

                        (t/is (= (:touched group) #{:shapes-group}))
                        (t/is (nil? (:touched shape1)))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (nil? (:shape-ref shape1)))
                        (t/is (nil? (:touched shape2)))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape1))))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape2)} (:id instance1) 0)
          :the/end)))))

(t/deftest test-touched-children-delete
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)
                                           (thp/id :shape2)]))

            shape1    (thp/get-shape state :shape1)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Component-1*           #--> Component-1
                      ;       #{:shapes-group}
                      ;     Rect 2               ---> Rect 2
                      ;
                      ; [Component-1]
                      ; Component-1
                      ;   Rect 1
                      ;   Rect 2
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape2] [c-group c-shape2] component]
                            (thl/resolve-instance-and-main-allow-dangling
                              new-state
                              (:id instance1))]

                        (t/is (= (:touched group) #{:shapes-group}))
                        (t/is (nil? (:touched shape2)))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape2))))))]

        (ptk/emit!
          store
          (dwc/delete-shapes #{(:id shape1)})
          :the/end)))))

(t/deftest test-touched-children-move
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/sample-shape :shape3 :rect
                                        {:name "Rect 3"})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)
                                           (thp/id :shape2)
                                           (thp/id :shape3)]))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Component-1*        #--> Component-1
                      ;       #{:shapes-group}
                      ;     Rect 2            ---> Rect 2
                      ;     Rect 1            ---> Rect 1
                      ;     Rect 3            ---> Rect 3
                      ;
                      ; [Component-1]
                      ; Component-1
                      ;   Rect 1
                      ;   Rect 2
                      ;   Rect 3
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1 shape2 shape3]
                             [c-group c-shape1 c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main-allow-dangling
                              new-state
                              (:id instance1))]

                        (t/is (= (:touched group) #{:shapes-group}))
                        (t/is (nil? (:touched shape1)))
                        (t/is (some? (:shape-ref shape1)))
                        (t/is (= (:name shape1) "Rect 2"))
                        (t/is (nil? (:touched shape2)))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (nil? (:touched shape3)))
                        (t/is (some? (:shape-ref shape3)))
                        (t/is (= (:name shape3) "Rect 3"))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape1)))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (nil? (:touched c-shape2)))
                        (t/is (= (:name c-shape2) "Rect 2"))
                        (t/is (nil? (:touched c-shape3)))
                        (t/is (= (:name c-shape3) "Rect 3")))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape1)} (:id instance1) 2)
          :the/end)))))

(t/deftest test-reset-changes
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)]))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ; 
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [shape1 (thp/get-shape new-state :shape1)

                            [[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (= (:fill-color shape1) clr/white))
                        (t/is (= (:fill-opacity shape1) 1))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/white))
                        (t/is (= (:fill-opacity c-shape1) 1))
                        (t/is (= (:touched c-shape1) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          (dwl/reset-component (:id instance1))
          :the/end)))))

(t/deftest test-reset-children-add
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)])
                      (thp/sample-shape :shape2 :circle
                                        {:name "Circle 1"}))

            instance1 (thp/get-shape state :instance1)
            shape2    (thp/get-shape state :shape2)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (nil? (:touched instance1)))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (some? (:shape-ref shape1))))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape2)} (:id instance1) 0)
          (dwl/reset-component (:id instance1))
          :the/end)))))

(t/deftest test-reset-children-delete
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)
                                           (thp/id :shape2)]))

            instance1 (thp/get-shape state :instance1)
            shape1    (thp/get-shape state :shape1)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (nil? (:touched instance1)))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (some? (:shape-ref shape1))))))]

        (ptk/emit!
          store
          (dwc/delete-shapes #{(:id shape1)})
          (dwl/reset-component (:id instance1))
          :the/end)))))

(t/deftest test-reset-children-move
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/sample-shape :shape3 :rect
                                        {:name "Rect 3"})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)
                                           (thp/id :shape2)
                                           (thp/id :shape3)]))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Component-1         #--> Component-1
                      ;     Rect 1            ---> Rect 1
                      ;     Rect 2            ---> Rect 2
                      ;     Rect 3            ---> Rect 3
                      ;
                      ; [Component-1]
                      ; Component-1
                      ;   Rect 1
                      ;   Rect 2
                      ;   Rect 3
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1 shape2 shape3] [c-group c-shape1 c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (nil? (:touched group)))
                        (t/is (nil? (:touched shape1)))
                        (t/is (some? (:shape-ref shape1)))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (nil? (:touched shape2)))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (nil? (:touched shape3)))
                        (t/is (some? (:shape-ref shape3)))
                        (t/is (= (:name shape3) "Rect 3"))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape1)))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (nil? (:touched c-shape2)))
                        (t/is (= (:name c-shape2) "Rect 2"))
                        (t/is (nil? (:touched c-shape3)))
                        (t/is (= (:name c-shape3) "Rect 3")))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape1)} (:id instance1) 2)
          (dwl/reset-component (:id instance1))
          :the/end)))))

(t/deftest test-update-component
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)]))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ; 
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/test))
                        (t/is (= (:fill-opacity c-shape1) 0.5))
                        (t/is (= (:touched c-shape1) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          (dwl/update-component (:id instance1))
          :the/end)))))

(t/deftest test-update-component-and-sync
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)])
                      (thp/instantiate-component :instance2
                                                 (thp/id :component-1)))

            file      (wsh/get-local-file state)

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Rect 1-2            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [instance2 (thp/get-shape state :instance2)

                            [[group shape2] [c-group c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:fill-opacity shape2) 0.5))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/test))
                        (t/is (= (:fill-opacity c-shape2) 0.5))
                        (t/is (= (:touched c-shape2) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          (dwl/update-component-sync (:id instance1) (:id file))
          :the/end)))))

(t/deftest test-update-preserve-touched
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)])
                      (thp/instantiate-component :instance2
                                                 (thp/id :component-1)))

            file      (wsh/get-local-file state)

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)
            instance2 (thp/get-shape state :instance2)

            shape2    (cph/get-shape (wsh/lookup-page state)
                                     (first (:shapes instance2)))

            update-fn1 (fn [shape]
                         (merge shape {:fill-color clr/test
                                       :stroke-width 0.5}))

            update-fn2 (fn [shape]
                         (merge shape {:stroke-width 0.2}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Rect 1-2            #--> Rect 1-1
                      ;     Rect 1*           ---> Rect 1
                      ;         #{:stroke-group}
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [instance2 (thp/get-shape state :instance2)

                            [[group shape2] [c-group c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:stroke-width shape2) 0.2))
                        (t/is (= (:touched shape2 #{:stroke-group})))
                        (t/is (= (:fill-color c-shape2) clr/test))
                        (t/is (= (:stroke-width c-shape2) 0.5))
                        (t/is (= (:touched c-shape2) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn1)
          (dch/update-shapes [(:id shape2)] update-fn2)
          (dwl/update-component-sync (:id instance1) (:id file))
          :the/end)))))

(t/deftest test-update-children-add
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)])
                      (thp/sample-shape :shape2 :circle
                                        {:name "Circle 1"}))

            file      (wsh/get-local-file state)

            instance1 (thp/get-shape state :instance1)
            shape2    (thp/get-shape state :shape2)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> Rect 1-1
                      ;     Circle 1          ---> Circle 1
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Circle 1
                      ;   Rect 1
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1 shape2] [c-group c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (nil? (:touched group)))
                        (t/is (nil? (:touched shape1)))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (some? (:shape-ref shape1)))
                        (t/is (nil? (:touched shape2)))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape1)))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (nil? (:touched c-shape2)))
                        (t/is (= (:name c-shape2) "Rect 1")))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape2)} (:id instance1) 0)
          (dwl/update-component-sync (:id instance1) (:id file))
          :the/end)))))

(t/deftest test-update-children-delete
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)
                                           (thp/id :shape2)]))

            file      (wsh/get-local-file state)

            instance1 (thp/get-shape state :instance1)
            shape1    (thp/get-shape state :shape1)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Component-1            #--> Component-1
                      ;     Rect 2               ---> Rect 2
                      ;
                      ; [Component-1]
                      ; Component-1
                      ;   Rect 2
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape2] [c-group c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (nil? (:touched group)))
                        (t/is (nil? (:touched shape2)))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape2)))
                        (t/is (= (:name c-shape2) "Rect 2")))))]

        (ptk/emit!
          store
          (dwc/delete-shapes #{(:id shape1)})
          (dwl/update-component-sync (:id instance1) (:id file))
          :the/end)))))

(t/deftest test-update-children-move
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"})
                      (thp/sample-shape :shape2 :rect
                                        {:name "Rect 2"})
                      (thp/sample-shape :shape3 :rect
                                        {:name "Rect 3"})
                      (thp/make-component :instance1 :component-1
                                          [(thp/id :shape1)
                                           (thp/id :shape2)
                                           (thp/id :shape3)]))

            file      (wsh/get-local-file state)

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Component-1         #--> Component-1
                      ;     Rect 2            ---> Rect 2
                      ;     Rect 1            ---> Rect 1
                      ;     Rect 3            ---> Rect 3
                      ;
                      ; [Component-1]
                      ; Component-1
                      ;   Rect 2
                      ;   Rect 1
                      ;   Rect 3
                      ;
                      (let [instance1 (thp/get-shape new-state :instance1)

                            [[group shape1 shape2 shape3] [c-group c-shape1 c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (nil? (:touched group)))
                        (t/is (nil? (:touched shape1)))
                        (t/is (some? (:shape-ref shape1)))
                        (t/is (= (:name shape1) "Rect 2"))
                        (t/is (nil? (:touched shape2)))
                        (t/is (some? (:shape-ref shape2)))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (nil? (:touched shape3)))
                        (t/is (some? (:shape-ref shape3)))
                        (t/is (= (:name shape3) "Rect 3"))
                        (t/is (nil? (:touched c-group)))
                        (t/is (nil? (:touched c-shape1)))
                        (t/is (= (:name c-shape1) "Rect 2"))
                        (t/is (nil? (:touched c-shape2)))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (nil? (:touched c-shape3)))
                        (t/is (= (:name c-shape3) "Rect 3")))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape1)} (:id instance1) 2)
          (dwl/update-component-sync (:id instance1) (:id file))
          :the/end)))))
