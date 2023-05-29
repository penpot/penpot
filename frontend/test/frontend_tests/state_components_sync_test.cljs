;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.state-components-sync-test
  (:require
   [app.common.colors :as clr]
   [app.common.types.file :as ctf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.events :as the]
   [frontend-tests.helpers.libraries :as thl]
   [frontend-tests.helpers.pages :as thp]
   [potok.core :as ptk]))

;; (t/use-fixtures :each
;;   {:before thp/reset-idmap!})

;; ; === Test touched ======================

(t/deftest test-touched
  (t/async done
    (let [state (-> thp/initial-state
                    (thp/sample-page)
                    (thp/sample-shape :shape1 :rect
                                      {:name "Rect 1"
                                       :fill-color clr/white
                                       :fill-opacity 1})
                    (thp/make-component :main1 :component1
                                        [(thp/id :shape1)])
                    (thp/instantiate-component :instance1
                                               (thp/id :component1)))

          [_group1 shape1']
          (thl/resolve-instance state (thp/id :instance1))

          store (the/prepare-store state done
                                   (fn [new-state]
                                     ;; Uncomment to debug
                                     ;; (ctf/dump-tree (get new-state :workspace-data)
                                     ;;                (get new-state :current-page-id)
                                     ;;                (get new-state :workspace-libraries)
                                     ;;                false true)
                                     ; Expected shape tree:
                                     ;
                                     ; [Page]
                                     ; Root Frame
                                     ;   Rect 1
                                     ;     Rect 1
                                     ;   Rect 1              #--> Rect 1
                                     ;     Rect 1*           ---> Rect 1
                                     ;         #{:fill-group}
                                     ;
                                     ; [Rect 1]
                                     ;   page1 / Rect 1
                                     ;
                                     (let [[[group shape1] [c-group c-shape1] _component]
                                           (thl/resolve-instance-and-main
                                            new-state
                                            (thp/id :instance1))]

                                       (t/is (= (:name group) "Rect 1"))
                                       (t/is (= (:touched group) nil))
                                       (t/is (= (:name shape1) "Rect 1"))
                                       (t/is (= (:touched shape1) #{:fill-group}))
                                       (t/is (= (:fill-color shape1) clr/test))
                                       (t/is (= (:fill-opacity shape1) 0.5))

                                       (t/is (= (:name c-group) "Rect 1"))
                                       (t/is (= (:touched c-group) nil))
                                       (t/is (= (:name c-shape1) "Rect 1"))
                                       (t/is (= (:touched c-shape1) nil))
                                       (t/is (= (:fill-color c-shape1) clr/white))
                                       (t/is (= (:fill-opacity c-shape1) 1))
                                       )))]

      (ptk/emit!
       store
       (dch/update-shapes [(:id shape1')]
                          (fn [shape]
                            (merge shape {:fill-color clr/test
                                          :fill-opacity 0.5})))
       :the/end))))

(t/deftest test-touched-children-add
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
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
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Rect 1*             #--> Rect 1
                                    ;       #{:shapes-group}
                                    ;     Circle 1
                                    ;     Rect 1            ---> Rect 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[group shape1 shape2] [c-group c-shape1] _component]
                                          (thl/resolve-instance-and-main-allow-dangling
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Rect 1"))
                                      (t/is (= (:touched group) #{:shapes-group}))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (not= (:shape-ref shape2) nil))

                                      (t/is (= (:name c-group) "Rect 1"))
                                      (t/is (= (:touched c-group) nil))
                                      (t/is (= (:shape-ref c-group) nil))
                                      (t/is (= (:name c-shape1) "Rect 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:shape-ref c-shape1) nil)))))]

     (ptk/emit!
      store
      (dw/relocate-shapes #{(:id shape2)} (:id instance1) 0)
      :the/end))))

(t/deftest test-touched-children-delete
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect 2"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)
                                        (thp/id :shape2)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         [_group1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Component 1
                                    ;     Rect 1
                                    ;     Rect 2
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 1*           ---> Rect 1
                                    ;         #{:visibility-group}
                                    ;     Rect 2            ---> Rect 2
                                    ;
                                    ; [Component 1]
                                    ;   page1 / Component 1
                                    ;
                                    (let [[[group shape1 shape2] [c-group c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main-allow-dangling
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Component 1"))
                                      (t/is (= (:touched group) nil))
                                      (t/is (not= (:shape-ref group) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:hidden shape1) true))  ; Instance shapes are not deleted but hidden
                                      (t/is (= (:touched shape1) #{:visibility-group}))
                                      (t/is (not= (:shape-ref shape1) nil))
                                      (t/is (= (:name shape2) "Rect 2"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (not= (:shape-ref shape2) nil))

                                      (t/is (= (:name c-group) "Component 1"))
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
      (dwsh/delete-shapes #{(:id shape1')})
      :the/end))))

(t/deftest test-touched-children-move
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect 2"})
                   (thp/sample-shape :shape3 :rect
                                     {:name "Rect 3"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)
                                        (thp/id :shape2)
                                        (thp/id :shape3)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         [group1' shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Component 1
                                    ;     Rect 1
                                    ;     Rect 2
                                    ;     Rect 3
                                    ;   Component 1*        #--> Component 1
                                    ;       #{:shapes-group}
                                    ;     Rect 2            ---> Rect 2
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 3            ---> Rect 3
                                    ;
                                    ; [Component 1]
                                    ;   page1 / Component 1
                                    ;
                                    (let [[[group shape1 shape2 shape3]
                                           [c-group c-shape1 c-shape2 c-shape3] _component]
                                          (thl/resolve-instance-and-main-allow-dangling
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Component 1"))
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

                                      (t/is (= (:name c-group) "Component 1"))
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
      (dw/relocate-shapes #{(:id shape1')} (:id group1') 2)
      :the/end))))

(t/deftest test-touched-from-lib
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/move-to-library :lib1 "Library 1")
                   (thp/sample-page)
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)
                                              (thp/id :lib1)))

         [_group1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1              #--> <Library 1> Rect 1
                                    ;     Rect 1*           ---> <Library 1> Rect 1
                                    ;         #{:fill-group}
                                    ;
                                    (let [[[group shape1] [c-group c-shape1] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Rect 1"))
                                      (t/is (= (:touched group) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:touched shape1) #{:fill-group}))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:fill-opacity shape1) 0.5))

                                      (t/is (= (:name c-group) "Rect 1"))
                                      (t/is (= (:touched c-group) nil))
                                      (t/is (= (:name c-shape1) "Rect 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:fill-color c-shape1) clr/white))
                                      (t/is (= (:fill-opacity c-shape1) 1)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      :the/end))))

(t/deftest test-touched-nested-upper
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :main2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2)))

         [_instance2 _instance1 shape1' _shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1*         ---> Circle 1
                                    ;         #{:fill-group}
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) #{:fill-group}))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:fill-opacity shape1) 0.5))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/white))
                                      (t/is (= (:fill-opacity shape2) 1))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
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
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      :the/end))))

(t/deftest test-touched-nested-lower-near
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :instance2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2)))

         [_instance2 _instance1 _shape1' shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1*         ---> Rect 1
                                    ;           #{:fill-group}
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) #{:fill-group}))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
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
      (dch/update-shapes [(:id shape2')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      :the/end))))

(t/deftest test-touched-nested-lower-remote
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :instance2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2)))

         [instance2 _instance1 _shape1' shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1*         ---> Rect 1
                                    ;           #{:fill-group}
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))
                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
                                      (t/is (= (:touched c-instance1) nil))
                                      (t/is (= (:name c-shape1) "Circle 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:fill-color c-shape1) clr/black))
                                      (t/is (= (:fill-opacity c-shape1) 0))
                                      (t/is (= (:name c-shape2) "Rect 1"))
                                      (t/is (= (:touched c-shape2) #{:fill-group}))
                                      )))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape2')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component (:id instance2))
      :the/end))))

;; ; === Test reset changes ======================

(t/deftest test-reset-changes
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1
                                    ;
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[group shape1] [c-group c-shape1] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (:id instance1))]

                                      (t/is (= (:name group) "Rect 1"))
                                      (t/is (= (:touched group) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:fill-color shape1) clr/white))
                                      (t/is (= (:fill-opacity shape1) 1))
                                      (t/is (= (:touched shape1) nil))

                                      (t/is (= (:name c-group) "Rect 1"))
                                      (t/is (= (:touched c-group) nil))
                                      (t/is (= (:name c-shape1) "Rect 1"))
                                      (t/is (= (:fill-color c-shape1) clr/white))
                                      (t/is (= (:fill-opacity c-shape1) 1))
                                      (t/is (= (:touched c-shape1) nil)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/reset-component (:id instance1))
      :the/end))))

(t/deftest test-reset-children-add
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
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
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[group shape1] [c-group c-shape1] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Rect 1"))
                                      (t/is (= (:touched group) nil))
                                      (t/is (not= (:shape-ref group) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (not= (:shape-ref shape1) nil))

                                      (t/is (= (:name c-group) "Rect 1"))
                                      (t/is (= (:touched c-group) nil))
                                      (t/is (= (:shape-ref c-group) nil))
                                      (t/is (= (:name c-shape1) "Rect 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:shape-ref c-shape1) nil)))))]

     (ptk/emit!
      store
      (dw/relocate-shapes #{(:id shape2)} (:id instance1) 0)
      (dwl/reset-component (:id instance1))
      :the/end))))

(t/deftest test-reset-children-delete
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect 2"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)
                                        (thp/id :shape2)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Component 1
                                    ;     Rect 1
                                    ;     Rect 2
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 2            ---> Rect 2
                                    ;
                                    ; [Component 1]
                                    ;   page1 / Component 1
                                    ;
                                    (let [[[group shape1 shape2]
                                           [c-group c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Component 1"))
                                      (t/is (= (:touched group) nil))
                                      (t/is (not= (:shape-ref group) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (not= (:shape-ref shape1) nil))
                                      (t/is (= (:name shape2) "Rect 2"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (not= (:shape-ref shape2) nil))

                                      (t/is (= (:name c-group) "Component 1"))
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
      (dwsh/delete-shapes #{(:id shape1')})
      (dwl/reset-component (:id instance1))
      :the/end))))

(t/deftest test-reset-children-move
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect 2"})
                   (thp/sample-shape :shape3 :rect
                                     {:name "Rect 3"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)
                                        (thp/id :shape2)
                                        (thp/id :shape3)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Component 1
                                    ;     Rect 1
                                    ;     Rect 2
                                    ;     Rect 3
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 2            ---> Rect 2
                                    ;     Rect 3            ---> Rect 3
                                    ;
                                    ; [Component 1]
                                    ;   page1 / Component 1
                                    ;
                                    (let [[[group shape1 shape2 shape3] [c-group c-shape1 c-shape2 c-shape3] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))]

                                      (t/is (= (:name group) "Component 1"))
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

                                      (t/is (= (:name c-group) "Component 1"))
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
      (dw/relocate-shapes #{(:id shape1')} (:id instance1) 2)
      (dwl/reset-component (:id instance1))
      :the/end))))

(t/deftest test-reset-from-lib
  (t/async done
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color clr/white
                                         :fill-opacity 1})
                      (thp/make-component :instance1 :component1
                                          [(thp/id :shape1)])
                      (thp/move-to-library :lib1 "Library 1")
                      (thp/sample-page)
                      (thp/instantiate-component :instance2
                                                 (thp/id :component1)
                                                 (thp/id :lib1)))

            [instance2 shape2]
            (thl/resolve-instance state (thp/id :instance2))

            store (the/prepare-store state done
                    (fn [new-state]
                      ; Expected shape tree:
                      ;
                      ; [Page]
                      ; Root Frame
                      ;   Rect 1              #--> <Library 1> Rect 1
                      ;     Rect 1            ---> <Library 1> Rect 1
                      ;
                      (let [[[group shape1] [c-group c-shape1] _component]
                            (thl/resolve-instance-and-main
                             new-state
                             (:id instance2))]

                        (t/is (= (:name group) "Rect 1"))
                        (t/is (= (:touched group) nil))
                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:fill-color shape1) clr/white))
                        (t/is (= (:fill-opacity shape1) 1))
                        (t/is (= (:touched shape1) nil))

                        (t/is (= (:name c-group) "Rect 1"))
                        (t/is (= (:touched c-group) nil))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:fill-color c-shape1) clr/white))
                        (t/is (= (:fill-opacity c-shape1) 1))
                        (t/is (= (:touched c-shape1) nil)))))]

        (ptk/emit!
          store
          (dch/update-shapes [(:id shape2)]
                             (fn [shape]
                               (merge shape {:fill-color clr/test
                                             :fill-opacity 0.5})))
          (dwl/reset-component (:id instance2))
          :the/end))))

(t/deftest test-reset-nested-upper
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :main2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2)))

         [instance2 _instance1 shape1' _shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/white))
                                      (t/is (= (:fill-opacity shape2) 1))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
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
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/reset-component (:id instance2))
      :the/end))))

(t/deftest test-reset-nested-lower-near
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :instance2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2)))

         [instance2 instance1 _shape1' shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/white))
                                      (t/is (= (:fill-opacity shape2) 1))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
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
      (dch/update-shapes [(:id shape2')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component (:id instance1))
      (dwl/reset-component (:id instance2))
      :the/end))))

(t/deftest test-reset-nested-lower-remote
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :instance2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2)))

         [instance2 instance1 _shape1' shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1*         ---> Rect 1
                                    ;           #{:fill-group}
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;         (remote-synced)
                                    ;       Rect 1          ---> Rect 1
                                    ;           (remote-synced)
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/white))
                                      (t/is (= (:fill-opacity shape2) 1))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
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
      (dch/update-shapes [(:id shape2')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component (:id instance2))
      (dwl/reset-component (:id instance1))
      :the/end))))

;; === Test update component ======================

(t/deftest test-update-component
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1)))

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1    <== (not updated)
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[main1 shape1] [c-main1 c-shape1] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape2] [c-instance1 c-shape2] component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape3] [c-instance2 c-shape3] component3]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name main1) "Rect 1"))
                                      (t/is (= (:touched main1) nil))
                                      (t/is (= (:shape-ref main1) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:fill-opacity shape1) 0.5))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:shape-ref instance1) (:id c-main1)))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:shape-ref shape2) (:id c-shape1)))

                                      (t/is (= (:name instance2) "Rect 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:shape-ref instance2) (:id c-main1)))
                                      (t/is (= (:name shape3) "Rect 1"))
                                      (t/is (= (:fill-color shape3) clr/white))
                                      (t/is (= (:fill-opacity shape3) 1))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:shape-ref shape3) (:id c-shape1)))

                                      (t/is (= component1 component2 component3))
                                      (t/is (= c-main1 main1))
                                      (t/is (= c-shape1 shape1))
                                      (t/is (= c-instance1 c-main1))
                                      (t/is (= c-shape2 c-shape1))
                                      (t/is (= c-instance2 c-main1))
                                      (t/is (= c-shape3 c-shape1)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component (:id instance1))
      :the/end))))

(t/deftest test-update-component-and-sync
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1)))

         file      (wsh/get-local-file state)

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         [_instance2 _shape1'']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[main1 shape1] [c-main1 c-shape1] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape2] [c-instance1 c-shape2] component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape3] [c-instance2 c-shape3] component3]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name main1) "Rect 1"))
                                      (t/is (= (:touched main1) nil))
                                      (t/is (= (:shape-ref main1) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:fill-opacity shape1) 0.5))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:shape-ref instance1) (:id c-main1)))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:shape-ref shape2) (:id c-shape1)))

                                      (t/is (= (:name instance2) "Rect 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:shape-ref instance2) (:id c-main1)))
                                      (t/is (= (:name shape3) "Rect 1"))
                                      (t/is (= (:fill-color shape3) clr/test))
                                      (t/is (= (:fill-opacity shape3) 0.5))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:shape-ref shape3) (:id c-shape1)))

                                      (t/is (= component1 component2 component3))
                                      (t/is (= c-main1 main1))
                                      (t/is (= c-shape1 shape1))
                                      (t/is (= c-instance1 c-main1))
                                      (t/is (= c-shape2 c-shape1))
                                      (t/is (= c-instance2 c-main1))
                                      (t/is (= c-shape3 c-shape1)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component-sync (:id instance1) (:id file))
      :the/end))))

(t/deftest test-update-preserve-touched
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1)))

         file      (wsh/get-local-file state)

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         [_instance2 shape1'']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1            ---> Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Rect 1*           ---> Rect 1
                                    ;         #{:stroke-group}
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[main1 shape1] [c-main1 c-shape1] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape2] [c-instance1 c-shape2] component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape3] [c-instance2 c-shape3] component3]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name main1) "Rect 1"))
                                      (t/is (= (:touched main1) nil))
                                      (t/is (= (:shape-ref main1) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:stroke-width shape1) 0.5))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:shape-ref instance1) (:id c-main1)))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:stroke-width shape2) 0.5))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:shape-ref shape2) (:id c-shape1)))

                                      (t/is (= (:name instance2) "Rect 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:shape-ref instance2) (:id c-main1)))
                                      (t/is (= (:name shape3) "Rect 1"))
                                      (t/is (= (:fill-color shape3) clr/test))
                                      (t/is (= (:stroke-width shape3) 0.2))
                                      (t/is (= (:touched shape3) #{:stroke-group}))
                                      (t/is (= (:shape-ref shape3) (:id c-shape1)))

                                      (t/is (= component1 component2 component3))
                                      (t/is (= c-main1 main1))
                                      (t/is (= c-shape1 shape1))
                                      (t/is (= c-instance1 c-main1))
                                      (t/is (= c-shape2 c-shape1))
                                      (t/is (= c-instance2 c-main1))
                                      (t/is (= c-shape3 c-shape1)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :stroke-width 0.5})))
      (dch/update-shapes [(:id shape1'')]
                         (fn [shape]
                           (merge shape {:stroke-width 0.2})))
      (dwl/update-component-sync (:id instance1) (:id file))
      :the/end))))

(t/deftest test-update-children-add
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1))
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
                                    ;   Rect 1
                                    ;     Circle 1
                                    ;     Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;     Rect 1            ---> Rect 1
                                    ;   Rect 1              #--> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;     Rect 1            ---> Rect 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    (let [[[main1 shape1 shape2]
                                           [c-main1 c-shape1 c-shape2] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape3 shape4]
                                           [c-instance1 c-shape3 c-shape4] component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape5 shape6]
                                           [c-instance2 c-shape5 c-shape6] component3]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name main1) "Rect 1"))
                                      (t/is (= (:touched main1) nil))
                                      (t/is (= (:shape-ref main1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:shape-ref shape2) nil))

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:shape-ref instance1) (:id c-main1)))
                                      (t/is (= (:name shape3) "Circle 1"))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:shape-ref shape3) (:id c-shape1)))
                                      (t/is (= (:name shape4) "Rect 1"))
                                      (t/is (= (:touched shape4) nil))
                                      (t/is (= (:shape-ref shape4) (:id c-shape2)))

                                      (t/is (= (:name instance2) "Rect 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:shape-ref instance2) (:id c-main1)))
                                      (t/is (= (:name shape5) "Circle 1"))
                                      (t/is (= (:touched shape5) nil))
                                      (t/is (= (:shape-ref shape5) (:id c-shape1)))
                                      (t/is (= (:name shape6) "Rect 1"))
                                      (t/is (= (:touched shape6) nil))
                                      (t/is (= (:shape-ref shape4) (:id c-shape2)))

                                      (t/is (= component1 component2 component3))
                                      (t/is (= c-main1 main1))
                                      (t/is (= c-shape1 shape1))
                                      (t/is (= c-shape2 shape2))
                                      (t/is (= c-instance1 c-main1))
                                      (t/is (= c-shape3 c-shape1))
                                      (t/is (= c-shape4 c-shape2))
                                      (t/is (= c-instance2 c-main1))
                                      (t/is (= c-shape5 c-shape1))
                                      (t/is (= c-shape6 c-shape2)))))]

     (ptk/emit!
      store
      (dw/relocate-shapes #{(:id shape2)} (:id instance1) 0)
      (dwl/update-component-sync (:id instance1) (:id file))
      :the/end))))

(t/deftest test-update-children-delete
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect 2"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)
                                        (thp/id :shape2)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1)))

         file      (wsh/get-local-file state)

         [instance1 shape1' _shape2']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                   ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Component 1
                                    ;     Rect 1
                                    ;     Rect 2
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 2            ---> Rect 2
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 2            ---> Rect 2
                                    ;
                                    ; [Component 1]
                                    ;   page1 / Component 1
                                    ;
                                    (let [[[main1 shape1 shape2]
                                           [c-main1 c-shape1 c-shape2] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape3 shape4]
                                           [c-instance1 c-shape3 c-shape4] component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape5 shape6]
                                           [c-instance2 c-shape5 c-shape6] component3]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name main1) "Component 1"))
                                      (t/is (= (:touched main1) nil))
                                      (t/is (= (:shape-ref main1) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:hidden shape1) true))  ; Instance shapes are not deleted but hidden
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))
                                      (t/is (= (:name shape2) "Rect 2"))
                                      (t/is (= (:hidden shape2) nil))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:shape-ref shape2) nil))

                                      (t/is (= (:name instance1) "Component 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:shape-ref instance1) (:id c-main1)))
                                      (t/is (= (:name shape3) "Rect 1"))
                                      (t/is (= (:hidden shape3) true))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:shape-ref shape3) (:id c-shape1)))
                                      (t/is (= (:name shape4) "Rect 2"))
                                      (t/is (= (:hidden shape4) nil))
                                      (t/is (= (:touched shape4) nil))
                                      (t/is (= (:shape-ref shape4) (:id c-shape2)))

                                      (t/is (= (:name instance2) "Component 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:shape-ref instance2) (:id c-main1)))
                                      (t/is (= (:name shape5) "Rect 1"))
                                      (t/is (= (:hidden shape5) true))
                                      (t/is (= (:touched shape5) nil))
                                      (t/is (= (:shape-ref shape5) (:id c-shape1)))
                                      (t/is (= (:name shape6) "Rect 2"))
                                      (t/is (= (:hidden shape6) nil))
                                      (t/is (= (:touched shape6) nil))
                                      (t/is (= (:shape-ref shape6) (:id c-shape2)))

                                      (t/is (= component1 component2 component3))
                                      (t/is (= c-main1 main1))
                                      (t/is (= c-shape1 shape1))
                                      (t/is (= c-shape2 shape2))
                                      (t/is (= c-instance1 c-main1))
                                      (t/is (= c-shape3 c-shape1))
                                      (t/is (= c-shape4 c-shape2))
                                      (t/is (= c-instance2 c-main1))
                                      (t/is (= c-shape5 c-shape1))
                                      (t/is (= c-shape6 c-shape2)))))]

     (ptk/emit!
      store
      (dwsh/delete-shapes #{(:id shape1')})
      (dwl/update-component-sync (:id instance1) (:id file))
      :the/end))))

(t/deftest test-update-children-move
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect 2"})
                   (thp/sample-shape :shape3 :rect
                                     {:name "Rect 3"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)
                                        (thp/id :shape2)
                                        (thp/id :shape3)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1)))

         file      (wsh/get-local-file state)

         [instance1 shape1' _shape2' _shape3']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Component 1
                                    ;     Rect 2
                                    ;     Rect 1
                                    ;     Rect 3
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 2            ---> Rect 2
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 3            ---> Rect 3
                                    ;   Component 1         #--> Component 1
                                    ;     Rect 2            ---> Rect 2
                                    ;     Rect 1            ---> Rect 1
                                    ;     Rect 3            ---> Rect 3
                                    ;
                                    ; [Component 1]
                                    ;   page1 / Component 1
                                    ;
                                    (let [[[main1 shape1 shape2 shape3]
                                           [c-main1 c-shape1 c-shape2 c-shape3] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape4 shape5 shape6]
                                           [c-instance1 c-shape4 c-shape5 c-shape6] component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape7 shape8 shape9]
                                           [c-instance2 c-shape7 c-shape8 c-shape9] component3]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name main1) "Component 1"))
                                      (t/is (= (:touched main1) nil))
                                      (t/is (= (:shape-ref main1) nil))
                                      (t/is (= (:name shape1) "Rect 2"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:shape-ref shape1) nil))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:shape-ref shape2) nil))
                                      (t/is (= (:name shape3) "Rect 3"))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:shape-ref shape3) nil))

                                      (t/is (= (:name instance1) "Component 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:shape-ref instance1) (:id c-main1)))
                                      (t/is (= (:name shape4) "Rect 2"))
                                      (t/is (= (:touched shape4) nil))
                                      (t/is (= (:shape-ref shape4) (:id c-shape1)))
                                      (t/is (= (:name shape5) "Rect 1"))
                                      (t/is (= (:touched shape5) nil))
                                      (t/is (= (:shape-ref shape5) (:id c-shape2)))
                                      (t/is (= (:name shape6) "Rect 3"))
                                      (t/is (= (:touched shape6) nil))
                                      (t/is (= (:shape-ref shape6) (:id c-shape3)))

                                      (t/is (= (:name instance2) "Component 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:shape-ref instance2) (:id c-main1)))
                                      (t/is (= (:name shape7) "Rect 2"))
                                      (t/is (= (:touched shape7) nil))
                                      (t/is (= (:shape-ref shape7) (:id c-shape1)))
                                      (t/is (= (:name shape8) "Rect 1"))
                                      (t/is (= (:touched shape8) nil))
                                      (t/is (= (:shape-ref shape8) (:id c-shape2)))
                                      (t/is (= (:name shape9) "Rect 3"))
                                      (t/is (= (:touched shape9) nil))
                                      (t/is (= (:shape-ref shape9) (:id c-shape3)))

                                      (t/is (= component1 component2 component3))
                                      (t/is (= c-main1 main1))
                                      (t/is (= c-shape1 shape1))
                                      (t/is (= c-shape2 shape2))
                                      (t/is (= c-shape3 shape3))
                                      (t/is (= c-instance1 c-main1))
                                      (t/is (= c-shape4 c-shape4))
                                      (t/is (= c-shape5 c-shape5))
                                      (t/is (= c-shape6 c-shape6))
                                      (t/is (= c-instance2 c-main1))
                                      (t/is (= c-shape7 c-shape7))
                                      (t/is (= c-shape8 c-shape8))
                                      (t/is (= c-shape9 c-shape9)))))]

     (ptk/emit!
      store
      (dw/relocate-shapes #{(:id shape1')} (:id instance1) 2)
      (dwl/update-component-sync (:id instance1) (:id file))
      :the/end))))

(t/deftest test-update-from-lib
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/move-to-library :lib1 "Library 1")
                   (thp/sample-page)
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)
                                              (thp/id :lib1))
                   (thp/instantiate-component :instance2
                                              (thp/id :component1)
                                              (thp/id :lib1)))

         [instance1 shape1']
         (thl/resolve-instance state (thp/id :instance1))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1              #--> <Library 1> Rect 1
                                    ;     Rect 1            ---> <Library 1> Rect 1
                                    ;   Rect 1              #--> <Library 1> Rect 1
                                    ;     Rect 1            ---> <Library 1> Rect 1
                                    ;
                                    (let [[[instance1 shape1] [c-instance1 c-shape1] _component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          [[instance2 shape2] [_c-instance2 _c-shape2] _component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))]

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Rect 1"))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:fill-opacity shape1) 0.5))
                                      (t/is (= (:touched shape1) nil))

                                      (t/is (= (:name c-instance1) "Rect 1"))
                                      (t/is (= (:touched c-instance1) nil))
                                      (t/is (= (:name c-shape1) "Rect 1"))
                                      (t/is (= (:fill-color c-shape1) clr/test))
                                      (t/is (= (:fill-opacity c-shape1) 0.5))
                                      (t/is (= (:touched c-shape1) nil))

                                      (t/is (= (:name instance2) "Rect 1"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))
                                      (t/is (= (:touched shape2) nil)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component-sync (:id instance1) (thp/id :lib1))
      :the/end))))

(t/deftest test-update-nested-upper
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :main2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2))
                   (thp/instantiate-component :instance3
                                              (thp/id :component2)))

         file      (wsh/get-local-file state)

         [instance2 _instance1 shape1' _shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))

                                          [[instance4 instance3 shape3 shape4]
                                           [_c-instance4 _c-instance3 _c-shape3 _c-shape4] _component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance3))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/test))
                                      (t/is (= (:fill-opacity shape1) 0.5))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/white))
                                      (t/is (= (:fill-opacity shape2) 1))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
                                      (t/is (= (:touched c-instance1) nil))
                                      (t/is (= (:name c-shape1) "Circle 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:fill-color c-shape1) clr/test))
                                      (t/is (= (:fill-opacity c-shape1) 0.5))
                                      (t/is (= (:name c-shape2) "Rect 1"))
                                      (t/is (= (:touched c-shape2) nil))
                                      (t/is (= (:fill-color c-shape2) clr/white))
                                      (t/is (= (:fill-opacity c-shape2) 1))

                                      (t/is (= (:name instance4) "Board"))
                                      (t/is (= (:touched instance4) nil))
                                      (t/is (= (:name instance3) "Rect 1"))
                                      (t/is (= (:touched instance3) nil))
                                      (t/is (= (:name shape3) "Circle 1"))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:fill-color shape3) clr/test))
                                      (t/is (= (:fill-opacity shape3) 0.5))
                                      (t/is (= (:name shape4) "Rect 1"))
                                      (t/is (= (:touched shape4) nil))
                                      (t/is (= (:fill-color shape4) clr/white))
                                      (t/is (= (:fill-opacity shape4) 1)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape1')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component-sync (:id instance2) (:id file))
      :the/end))))

(t/deftest test-update-nested-lower-near
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :main2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2))
                   (thp/instantiate-component :instance3
                                              (thp/id :component2)))

         file      (wsh/get-local-file state)

         [instance2 instance1 _shape1' shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))

                                          [[instance4 instance3 shape3 shape4]
                                           [_c-instance4 _c-instance3 _c-shape3 _c-shape4] _component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance3))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
                                      (t/is (= (:touched c-instance1) nil))
                                      (t/is (= (:name c-shape1) "Circle 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:fill-color c-shape1) clr/black))
                                      (t/is (= (:fill-opacity c-shape1) 0))
                                      (t/is (= (:name c-shape2) "Rect 1"))
                                      (t/is (= (:touched c-shape2) nil))
                                      (t/is (= (:fill-color c-shape2) clr/test))
                                      (t/is (= (:fill-opacity c-shape2) 0.5))

                                      (t/is (= (:name instance4) "Board"))
                                      (t/is (= (:touched instance4) nil))
                                      (t/is (= (:name instance3) "Rect 1"))
                                      (t/is (= (:touched instance3) nil))
                                      (t/is (= (:name shape3) "Circle 1"))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:fill-color shape3) clr/black))
                                      (t/is (= (:fill-opacity shape3) 0))
                                      (t/is (= (:name shape4) "Rect 1"))
                                      (t/is (= (:touched shape4) nil))
                                      (t/is (= (:fill-color shape4) clr/test))
                                      (t/is (= (:fill-opacity shape4) 0.5)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape2')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component (:id instance1))
      (dwl/update-component-sync (:id instance2) (:id file))
      :the/end))))

(t/deftest test-update-nested-lower-remote
  (t/async done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"
                                      :fill-color clr/white
                                      :fill-opacity 1})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1))
                   (thp/sample-shape :shape2 :circle
                                     {:name "Circle 1"
                                      :fill-color clr/black
                                      :fill-opacity 0})
                   (thp/frame-shapes :frame1
                                     [(thp/id :instance1)
                                      (thp/id :shape2)])
                   (thp/make-component :main2 :component2
                                       [(thp/id :frame1)])
                   (thp/instantiate-component :instance2
                                              (thp/id :component2))
                   (thp/instantiate-component :instance3
                                              (thp/id :component2)))

         file      (wsh/get-local-file state)

         [_instance2 instance1 _shape1' shape2']
         (thl/resolve-instance state (thp/id :instance2))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]
                                    ; Root Frame
                                    ;   Rect 1
                                    ;     Rect 1
                                    ;   Group
                                    ;     Rect 1            #--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;         (remote-synced)
                                    ;       Rect 1          ---> Rect 1
                                    ;           (remote-synced)
                                    ;     Circle 1          ---> Circle 1
                                    ;   Group               #--> Group
                                    ;     Rect 1            @--> Rect 1
                                    ;       Rect 1          ---> Rect 1
                                    ;     Circle 1          ---> Circle 1
                                    ;
                                    ; [Rect 1]
                                    ;   page1 / Rect 1
                                    ;
                                    ; [Group]
                                    ;   page1 / Group
                                    ;
                                    (let [[[instance2 instance1 shape1 shape2]
                                           [c-instance2 c-instance1 c-shape1 c-shape2] _component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance2))

                                          [[instance4 instance3 shape3 shape4]
                                           [_c-instance4 _c-instance3 _c-shape3 _c-shape4] _component2]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance3))]

                                      (t/is (= (:name instance2) "Board"))
                                      (t/is (= (:touched instance2) nil))
                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (= (:name shape1) "Circle 1"))
                                      (t/is (= (:touched shape1) nil))
                                      (t/is (= (:fill-color shape1) clr/black))
                                      (t/is (= (:fill-opacity shape1) 0))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (= (:fill-color shape2) clr/test))
                                      (t/is (= (:fill-opacity shape2) 0.5))

                                      (t/is (= (:name c-instance2) "Board"))
                                      (t/is (= (:touched c-instance2) nil))
                                      (t/is (= (:name c-instance1) "Rect 1"))
                                      (t/is (= (:touched c-instance1) nil))
                                      (t/is (= (:name c-shape1) "Circle 1"))
                                      (t/is (= (:touched c-shape1) nil))
                                      (t/is (= (:fill-color c-shape1) clr/black))
                                      (t/is (= (:fill-opacity c-shape1) 0))
                                      (t/is (= (:name c-shape2) "Rect 1"))
                                      (t/is (= (:touched c-shape2) nil))
                                      (t/is (= (:fill-color c-shape2) clr/test))
                                      (t/is (= (:fill-opacity c-shape2) 0.5))

                                      (t/is (= (:name instance4) "Board"))
                                      (t/is (= (:touched instance4) nil))
                                      (t/is (= (:name instance3) "Rect 1"))
                                      (t/is (= (:touched instance3) nil))
                                      (t/is (= (:name shape3) "Circle 1"))
                                      (t/is (= (:touched shape3) nil))
                                      (t/is (= (:fill-color shape3) clr/black))
                                      (t/is (= (:fill-opacity shape3) 0))
                                      (t/is (= (:name shape4) "Rect 1"))
                                      (t/is (= (:touched shape4) nil))
                                      (t/is (= (:fill-color shape4) clr/test))
                                      (t/is (= (:fill-opacity shape4) 0.5)))))]

     (ptk/emit!
      store
      (dch/update-shapes [(:id shape2')]
                         (fn [shape]
                           (merge shape {:fill-color clr/test
                                         :fill-opacity 0.5})))
      (dwl/update-component-sync (:id instance1) (:id file))
      :the/end))))
