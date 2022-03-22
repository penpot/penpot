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

; === Test touched ======================

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
                      ;     Rect 1*           ---> Rect 1     (color, opacity)
                      ;         #{:fill-group}
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:touched shape1) #{:fill-group}))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
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
                      (let [[[group shape1 shape2] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main-allow-dangling
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) #{:shapes-group}))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:shape-ref shape1) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil)))))]

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
                      (let [[[group shape2] [c-group c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main-allow-dangling
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Component-1"))
                        (t/is (= (:touched group) #{:shapes-group}))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))

                        (t/is (= (:name c-group) "Component-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil))
                        (t/is (= (:name c-shape3) "Rect 2"))
                        (t/is (= (:touched c-shape3) nil))
                        (t/is (= (:shape-ref c-shape3) nil)))))]

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
                      (let [[[group shape1 shape2 shape3]
                             [c-group c-shape1 c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main-allow-dangling
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Component-1"))
                        (t/is (= (:touched group) #{:shapes-group}))
                        (t/is (= (:name shape1) "Rect 2"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (not= (:shape-ref shape1) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))
                        (t/is (= (:name shape3) "Rect 3"))
                        (t/is (= (:touched shape3) nil))
                        (t/is (not= (:shape-ref shape3) nil))

                        (t/is (= (:name c-group) "Component-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil))
                        (t/is (= (:name c-shape2) "Rect 2"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil))
                        (t/is (= (:name c-shape3) "Rect 3"))
                        (t/is (= (:touched c-shape3) nil))
                        (t/is (= (:shape-ref c-shape3) nil)))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape1)} (:id instance1) 2)
          :the/end)))))

(t/deftest test-touched-from-lib
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
                      (thp/move-to-library :lib1 "Library 1")
                      (thp/sample-page)
                      (thp/instantiate-component :instance2
                                                 (thp/id :component-1)
                                                 (thp/id :lib1)))

            [instance2 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> <Library 1> Rect 1-1
                      ;     Rect 1*           ---> <Library 1> Rect 1  (color, opacity)
                      ;         #{:fill-group}
                      ;
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:touched shape1) #{:fill-group}))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/white))
                        (t/is (= (:fill-opacity c-shape1) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          :the/end)))))

(t/deftest test-touched-nested-upper
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1*            #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1
                      ;     Circle 1*         ---> Circle 1   (color, opacity)
                      ;         #{:fill-group}
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) #{:fill-group}))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/white))
                        (t/is (= (:fill-opacity shape2) 1))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/white))
                        (t/is (= (:fill-opacity c-shape2) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          :the/end)))))

(t/deftest test-touched-nested-lower-near
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1*         ---> Rect 1     (color, opacity)
                      ;         #{:fill-group}
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) #{:fill-group}))
                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:fill-opacity shape2) 0.5))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/white))
                        (t/is (= (:fill-opacity c-shape2) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          :the/end)))))

(t/deftest test-touched-nested-lower-remote
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1     (color, opacity)
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1*           ---> Rect 1     (color, opacity)
                      ;         #{:fill-group}
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:fill-opacity shape2) 0.5))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) #{:fill-group}))
                        (t/is (= (:fill-color c-shape2) clr/test))
                        (t/is (= (:fill-opacity c-shape2) 0.5)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/update-component (:id instance2))
          :the/end)))))

; === Test reset changes ======================

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
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/white))
                        (t/is (= (:fill-opacity shape1) 1))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
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
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (not= (:shape-ref shape1) nil))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil)))))]

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
                      ;   Component-1         #--> Component-1
                      ;     Rect 1            ---> Rect 1
                      ;     Rect 2            ---> Rect 2
                      ;
                      ; [Component-1]
                      ; Component-1
                      ;   Rect 1
                      ;   Rect 2
                      ;
                      (let [[[group shape1 shape2]
                             [c-group c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Component-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (not= (:shape-ref shape1) nil))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))

                        (t/is (= (:name c-group) "Component-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil))
                        (t/is (= (:name c-shape2) "Rect 2"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil)))))]

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
                      (let [[[group shape1 shape2 shape3] [c-group c-shape1 c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Component-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (not= (:shape-ref shape1) nil))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))
                        (t/is (= (:name shape3) "Rect 3"))
                        (t/is (= (:touched shape3) nil))
                        (t/is (not= (:shape-ref shape3) nil))

                        (t/is (= (:name c-group) "Component-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil))
                        (t/is (= (:name c-shape2) "Rect 2"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil))
                        (t/is (= (:name c-shape3) "Rect 3"))
                        (t/is (= (:touched c-shape3) nil))
                        (t/is (= (:shape-ref c-shape3) nil)))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape1)} (:id instance1) 2)
          (dwl/reset-component (:id instance1))
          :the/end)))))

(t/deftest test-reset-from-lib
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
                      (thp/move-to-library :lib1 "Library 1")
                      (thp/sample-page)
                      (thp/instantiate-component :instance2
                                                 (thp/id :component-1)
                                                 (thp/id :lib1)))

            [instance2 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> <Library 1> Rect 1-1
                      ;     Rect 1            ---> <Library 1> Rect 1
                      ;
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/white))
                        (t/is (= (:fill-opacity shape1) 1))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:fill-color c-shape1) clr/white))
                        (t/is (= (:fill-opacity c-shape1) 1))
                        (t/is (= (:touched c-shape1) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/reset-component (:id instance2))
          :the/end)))))

(t/deftest test-reset-nested-upper
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/white))
                        (t/is (= (:fill-opacity shape2) 1))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/white))
                        (t/is (= (:fill-opacity c-shape2) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          (dwl/reset-component (:id instance2))
          :the/end)))))

(t/deftest test-reset-nested-lower-near
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1                                (color, opacity)
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/white))
                        (t/is (= (:fill-opacity shape2) 1))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/white))
                        (t/is (= (:fill-opacity c-shape2) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/update-component (:id instance1))
          (dwl/reset-component (:id instance2))
          :the/end)))))

(t/deftest test-reset-nested-lower-remote
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1     (color, opacity)
                      ;         #{:fill-group}
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/white))
                        (t/is (= (:fill-opacity shape2) 1))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) #{:fill-group}))
                        (t/is (= (:fill-color c-shape2) clr/test))
                        (t/is (= (:fill-opacity c-shape2) 0.5)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/update-component (:id instance2))
          (dwl/reset-component (:id instance1))
          :the/end)))))

; === Test update component ======================

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
                                          [(thp/id :shape1)])
                      (thp/instantiate-component :instance2
                                                 (thp/id :component-1)))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)
            instance2 (thp/get-shape state :instance2)

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
                      ;     Rect 1            ---> Rect 1     (color, opacity)
                      ;   Rect 1-2
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1                              (color, opacity)
                      ;
                      (let [[[instance1 shape1] [c-instance1 c-shape1] component1]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))

                            [[instance2 shape2] [c-instance2 c-shape2] component2]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name instance2) "Rect 1-2"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:fill-color shape2) clr/white))
                        (t/is (= (:fill-opacity shape2) 1))
                        (t/is (= (:touched shape2) nil))

                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:fill-color c-shape1) clr/test))
                        (t/is (= (:fill-opacity c-shape1) 0.5))
                        (t/is (= (:touched c-shape1) nil))

                        (t/is (= component1 component2))
                        (t/is (= c-instance2 c-instance1))
                        (t/is (= c-shape2 c-shape1)))))]

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
            instance2 (thp/get-shape state :instance2)

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
                      ;     Rect 1            ---> Rect 1     (color, opacity)
                      ;   Rect 1-2            #--> Rect 1-1
                      ;     Rect 1            ---> Rect 1     (color, opacity)
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1                              (color, opacity)
                      ;
                      (let [[[instance1 shape1] [c-instance1 c-shape1] component1]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))

                            [[instance2 shape2] [c-instance2 c-shape2] component2]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name instance2) "Rect 1-2"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:fill-opacity shape2) 0.5))
                        (t/is (= (:touched shape2) nil))

                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:fill-color c-shape1) clr/test))
                        (t/is (= (:fill-opacity c-shape1) 0.5))
                        (t/is (= (:touched c-shape1) nil))

                        (t/is (= component1 component2))
                        (t/is (= c-instance2 c-instance1))
                        (t/is (= c-shape2 c-shape1)))))]

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
                      ;     Rect 1            ---> Rect 1     (color, stroke)
                      ;   Rect 1-2            #--> Rect 1-1
                      ;     Rect 1*           ---> Rect 1     (color, stroke2)
                      ;         #{:stroke-group}
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1                              (color, stroke)
                      ;
                      (let [[[instance1 shape1] [c-instance1 c-shape1] component1]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance1))

                            [[instance2 shape2] [c-instance2 c-shape2] component2]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:stroke-width shape1) 0.5))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name instance2) "Rect 1-2"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:stroke-width shape2) 0.2))
                        (t/is (= (:touched shape2) #{:stroke-group}))

                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:fill-color c-shape1) clr/test))
                        (t/is (= (:stroke-width c-shape1) 0.5))
                        (t/is (= (:touched c-shape1) nil))

                        (t/is (= component1 component2))
                        (t/is (= c-instance2 c-instance1))
                        (t/is (= c-shape2 c-shape1)))))]

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
                      (let [[[group shape1 shape2]
                             [c-group c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (not= (:shape-ref shape1) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil)))))]

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
                      (let [[[group shape2] [c-group c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Component-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:name shape2) "Rect 2"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))

                        (t/is (= (:name c-group) "Component-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape2) "Rect 2"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil)))))]

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
                      (let [[[group shape1 shape2 shape3] [c-group c-shape1 c-shape2 c-shape3] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance1))]

                        (t/is (= (:name group) "Component-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (not= (:shape-ref group) nil))
                        (t/is (= (:touched shape1) nil))
                        (t/is (not= (:shape-ref shape1) nil))
                        (t/is (= (:name shape1) "Rect 2"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (not= (:shape-ref shape2) nil))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape3) nil))
                        (t/is (not= (:shape-ref shape3) nil))
                        (t/is (= (:name shape3) "Rect 3"))

                        (t/is (= (:name c-group) "Component-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:shape-ref c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 2"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:shape-ref c-shape1) nil))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:shape-ref c-shape2) nil))
                        (t/is (= (:name c-shape3) "Rect 3"))
                        (t/is (= (:touched c-shape3) nil))
                        (t/is (= (:shape-ref c-shape3) nil)))))]

        (ptk/emit!
          store
          (dw/relocate-shapes #{(:id shape1)} (:id instance1) 2)
          (dwl/update-component-sync (:id instance1) (:id file))
          :the/end)))))

(t/deftest test-update-from-lib
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
                      (thp/move-to-library :lib1 "Library 1")
                      (thp/sample-page)
                      (thp/instantiate-component :instance2
                                                 (thp/id :component-1)
                                                 (thp/id :lib1)))

            [instance2 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1-1            #--> <Library 1> Rect 1-1     (color, opacity)
                      ;     Rect 1            ---> <Library 1> Rect 1
                      ;
                      (let [[[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (:id instance2))]

                        (t/is (= (:name group) "Rect 1-1"))
                        (t/is (= (:touched group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name c-group) "Rect 1-1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:fill-color c-shape1) clr/test))
                        (t/is (= (:fill-opacity c-shape1) 0.5))
                        (t/is (= (:touched c-shape1) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/update-component (:id instance2))
          :the/end)))))

(t/deftest test-update-nested-upper
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1
                      ;     Circle 1          ---> Circle 1     (color, opacity)
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Circle 1                              (color, opacity)
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/test))
                        (t/is (= (:fill-opacity shape1) 0.5))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/white))
                        (t/is (= (:fill-opacity shape2) 1))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/test))
                        (t/is (= (:fill-opacity c-shape1) 0.5))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/white))
                        (t/is (= (:fill-opacity c-shape2) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape1)] update-fn)
          (dwl/update-component (:id instance2))
          :the/end)))))

(t/deftest test-update-nested-lower-near
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1     (color, opacity)
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1     (color, opacity)
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:fill-opacity shape2) 0.5))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/test))
                        (t/is (= (:fill-opacity c-shape2) 0.5)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/update-component (:id instance1))
          (dwl/update-component (:id instance2))
          :the/end)))))

(t/deftest test-update-nested-lower-remote
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
                                        {:name "Circle 1"
                                         :fill-color clr/black
                                         :fill-opacity 0})
                      (thp/group-shapes :group1
                                        [(thp/id :instance1)
                                         (thp/id :shape2)])
                      (thp/make-component :instance2 :component-2
                                          [(thp/id :group1)]))

            [instance2 instance1 shape1 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            update-fn (fn [shape]
                        (merge shape {:fill-color clr/test
                                      :fill-opacity 0.5}))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Group-1             #--> Group-1
                      ;     Rect 1-1          @--> Rect 1-1
                      ;       Rect 1          ---> Rect 1     (color, opacity)
                      ;     Circle 1          ---> Circle 1
                      ;
                      ; [Rect 1]
                      ; Rect 1-1
                      ;   Rect 1                              (color, opacity)
                      ;
                      ; [Group-1]
                      ; Group-1
                      ;   Rect 1-1            @--> Rect 1-1
                      ;     Rect 1            ---> Rect 1
                      ;   Circle 1
                      ;
                      (let [[[instance2 instance1 shape1 shape2]
                             [c-instance2 c-instance1 c-shape1 c-shape2] component]
                            (thl/resolve-instance-and-main
                              new-state
                              (thp/id :instance2))]

                        ; TODO: get and check the instance inside component [Group-1]

                        (t/is (= (:name instance2) "Group-1"))
                        (t/is (= (:touched instance2) nil))
                        (t/is (= (:name instance1) "Rect 1-1"))
                        (t/is (= (:touched instance1) nil))
                        (t/is (= (:name shape1) "Circle 1"))
                        (t/is (= (:touched shape1) nil))
                        (t/is (= (:fill-color shape1) clr/black))
                        (t/is (= (:fill-opacity shape1) 0))
                        (t/is (= (:name shape2) "Rect 1"))
                        (t/is (= (:touched shape2) nil))
                        (t/is (= (:fill-color shape2) clr/test))
                        (t/is (= (:fill-opacity shape2) 0.5))

                        (t/is (= (:name c-instance2) "Group-1"))
                        (t/is (= (:touched c-instance2) nil))
                        (t/is (= (:name c-instance1) "Rect 1-1"))
                        (t/is (= (:touched c-instance1) nil))
                        (t/is (= (:name c-shape1) "Circle 1"))
                        (t/is (= (:touched c-shape1) nil))
                        (t/is (= (:fill-color c-shape1) clr/black))
                        (t/is (= (:fill-opacity c-shape1) 0))
                        (t/is (= (:name c-shape2) "Rect 1"))
                        (t/is (= (:touched c-shape2) nil))
                        (t/is (= (:fill-color c-shape2) clr/white))
                        (t/is (= (:fill-opacity c-shape2) 1)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)] update-fn)
          (dwl/update-component (:id instance1))
          :the/end)))))

