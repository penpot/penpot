(ns frontend-tests.state-components-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.events :as the]
   [frontend-tests.helpers.libraries :as thl]
   [frontend-tests.helpers.pages :as thp]
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
                                        {:name "Rect 1"}))

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
                      ;   Rect 1              #--> Rect 1
                      ;     Rect 1            ---> Rect 1
                      ;
                      ; [Rect 1]
                      ; Rect 1
                      ;   Rect 1
                      ;
                      (let [shape1 (thp/get-shape new-state :shape1)

                            [[group shape1] [c-group c-shape1] component]
                            (thl/resolve-instance-and-main
                             new-state
                             (:parent-id shape1))

                            file (wsh/get-local-file new-state)]

                        (t/is (= (:name shape1) "Rect 1"))
                        (t/is (= (:name group) "Rect 1"))
                        (t/is (= (:name component) "Rect 1"))
                        (t/is (= (:name c-shape1) "Rect 1"))
                        (t/is (= (:name c-group) "Rect 1"))

                        (thl/is-from-file group file))))]

        (ptk/emit!
          store
          (dw/select-shape (thp/id :shape1))
          (dwl/add-component)
          :the/end)))))

(t/deftest test-add-component-from-several-shapes
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect-2"}))

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ; [Page]                                                                                                                                                                    
                   ; Root Frame                                                                                                                                                                
                   ;   Component 1                                                                        
                   ;     Rect 1           
                   ;     Rect-2                                                                           
                   ;                                                                                      
                   ; [Component 1]                                                                        
                   ;   page1 / Component 1                                                                                                                                                     
                   ;
                   (let [shape1 (thp/get-shape new-state :shape1)

                         [[group shape1 shape2]
                          [c-group c-shape1 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                          new-state
                          (:parent-id shape1))

                         file   (wsh/get-local-file new-state)]

                     (t/is (= (:name group) "Component 1"))
                     (t/is (= (:name shape1) "Rect 1"))
                     (t/is (= (:name shape2) "Rect-2"))
                     (t/is (= (:name component) "Component 1"))
                     (t/is (= (:name c-group) "Component 1"))
                     (t/is (= (:name c-shape1) "Rect 1"))
                     (t/is (= (:name c-shape2) "Rect-2"))

                     (thl/is-from-file group file))))]

     (ptk/emit!
       store
       (dw/select-shapes (lks/set (thp/id :shape1)
                                  (thp/id :shape2)))
       (dwl/add-component)
       :the/end))))

(t/deftest test-add-component-from-frame
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/sample-shape :shape2 :rect
                                     {:name "Rect-2"})
                   (thp/frame-shapes :frame1
                                     [(thp/id :shape1)
                                      (thp/id :shape2)]))

         store (the/prepare-store state done
                (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]                                                                                                                                                                    
                   ; Root Frame                                                                                                                                                                
                   ;   Group                                                                              
                   ;     Rect 1           
                   ;     Rect-2                                                                           
                   ;                                                                                      
                   ; [Group]                                                                              
                   ;   page1 / Group                                                                                                                                                           
                   ;
                  (let [[[group shape1 shape2]
                         [c-group c-shape1 c-shape2]
                         component]
                        (thl/resolve-instance-and-main
                         new-state
                         (thp/id :frame1))

                        file   (wsh/get-local-file new-state)]

                    (t/is (= (:name shape1) "Rect 1"))
                    (t/is (= (:name shape2) "Rect-2"))
                    (t/is (= (:name group) "Board"))
                    (t/is (= (:name component) "Board"))
                    (t/is (= (:name c-shape1) "Rect 1"))
                    (t/is (= (:name c-shape2) "Rect-2"))
                    (t/is (= (:name c-group) "Board"))

                    (thl/is-from-file group file))))]

     (ptk/emit!
       store
       (dw/select-shape (thp/id :frame1))
       (dwl/add-component)
       :the/end))))

(t/deftest test-add-component-from-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)]))

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]                                                                               
                   ; Root Frame                                                                           
                   ;   Rect 1                                                                                                                                                                  
                   ;     Rect 1                                                                                                                                                                
                   ;       Rect 1                                                                                                                                                              
                   ;                                                                                                                                                                           
                   ; [Rect 1]                                                                             
                   ;   page1 / Rect 1                                                                     
                   ;                                                                                      
                   ; [Rect 1]                                                                                                                                                                  
                   ;   page1 / Rect 1                                                                                                                                                          
                   ;
                   (let [[[instance1 shape1]
                          [c-instance1 c-shape1]
                          component1]
                         (thl/resolve-instance-and-main
                          new-state
                          (thp/id :main1)
                          true)

                         [[instance2 instance1' shape1']
                          [c-instance2 c-instance1' c-shape1']
                          component2]
                         (thl/resolve-instance-and-main
                          new-state
                          (:parent-id instance1))]

                     (t/is (= (:name shape1) "Rect 1"))
                     (t/is (= (:name instance1) "Rect 1"))
                     (t/is (= (:name component1) "Rect 1"))
                     (t/is (= (:name c-shape1) "Rect 1"))
                     (t/is (= (:name c-instance1) "Rect 1"))

                     (t/is (= (:name shape1') "Rect 1"))
                     (t/is (= (:name instance1') "Rect 1"))
                     (t/is (= (:name instance2) "Rect 1"))
                     (t/is (= (:name component2) "Rect 1"))
                     (t/is (= (:name c-shape1') "Rect 1"))
                     (t/is (= (:name c-instance1') "Rect 1"))
                     (t/is (= (:name c-instance2) "Rect 1")))))]

     (ptk/emit!
       store
       (dw/select-shape (thp/id :main1))
       (dwl/add-component)
       :the/end))))

(t/deftest test-rename-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)]))

         main1 (thp/get-shape state :main1)

         store (the/prepare-store state done
                (fn [new-state]
                  ; Expected shape tree:
                  ;
                  ; [Page]                                                                                                                                                                    
                  ; Root Frame                                                                           
                  ;   Rect 1             
                  ;     Rect 1                                                                           
                  ;                                                                                      
                  ; [Renamed component]                                                                  
                  ;   page1 / Rect 1                                                                                                                                                          
                  ;
                  (let [libs      (wsh/get-libraries new-state)
                        component (ctf/get-component libs
                                                     (:component-file main1)
                                                     (:component-id main1))]
                    (t/is (= (:name component)
                             "Renamed component")))))]

     (ptk/emit!
      store
      (dwl/rename-component (:component-id main1) "Renamed component")
      :the/end))))

(t/deftest test-duplicate-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)]))

         main1        (thp/get-shape state :main1)
         component-id (:component-id main1)

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
                   ; [Rect 1]                                                                             
                   ;   page1 / Rect 1                                                                                                                                                          
                   ;
                   (let [new-component-id (->> (get-in new-state
                                                       [:workspace-data
                                                        :components])
                                               (keys)
                                               (filter #(not= % component-id))
                                               (first))

                         [[_instance1 _shape1]
                          [_c-instance1 _c-shape1]
                          _component1]
                         (thl/resolve-instance-and-main
                          new-state
                          (:id main1))

                         [[_c-component2 _c-shape2]
                          component2]
                         (thl/resolve-component
                          new-state
                          new-component-id)]

                     (t/is (= (:name component2) "Rect 1")))))]

     (ptk/emit!
       store
       (dwl/duplicate-component thp/current-file-id component-id)
       :the/end))))

(t/deftest test-delete-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]                                                                                                                                                                                        
                                    ; Root Frame                                                                                     
                                    ;   Rect 1              #--> ?                                                                   
                                    ;     Rect 1            ---> ?                                                                   
                                    ;
                                    (let [[main1 shape1]
                                          (thl/resolve-noninstance
                                           new-state
                                           (thp/id :main1))

                                          [[instance1 shape2] [c-instance1 c-shape2] component1]
                                          (thl/resolve-instance-and-main-allow-dangling
                                           new-state
                                           (thp/id :instance1))

                                          file          (wsh/get-local-file new-state)
                                          component2    (ctkl/get-component file (thp/id :component1))
                                          component3    (ctkl/get-deleted-component file (thp/id :component1))
                                          
                                          saved-objects (:objects component3)
                                          saved-main1   (get saved-objects (:shape-ref instance1))
                                          saved-shape2  (get saved-objects (:shape-ref shape2))]

                                      (t/is (nil? main1))
                                      (t/is (nil? shape1))

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:touched instance1) nil))
                                      (t/is (not= (:shape-ref instance1) nil))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:touched shape2) nil))
                                      (t/is (not= (:shape-ref shape2) nil))
                                      (t/is (nil? c-instance1))
                                      (t/is (nil? c-shape2))
                                      (t/is (nil? component1))

                                      (t/is (nil? component2))

                                      (t/is (= (:name component3) "Rect 1"))
                                      (t/is (= (:deleted component3) true))
                                      (t/is (some? (:objects component3)))
                                      
                                      (t/is (= (:name saved-main1) "Rect 1"))
                                      (t/is (= (:name saved-shape2) "Rect 1")))))]

     (ptk/emit!
      store
      (dwl/delete-component {:id (thp/id :component1)})
      :the/end))))

(t/deftest test-restore-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         store (the/prepare-store state done
                                  (fn [new-state]
                                    ; Expected shape tree:
                                    ;
                                    ; [Page]                                                                                         
                                    ; Root Frame                                                                                     
                                    ;   Rect 1              #--> Rect 1                                                              
                                    ;     Rect 1            ---> Rect 1                                                              
                                    ;   Rect 1             
                                    ;     Rect 1                                                                                     
                                    ;                                                                                                
                                    ; [Rect 1]                                                                                       
                                    ;   page1 / Rect 1                                                                                                                                                                              
                                    ;
                                    (let [[[instance1 shape2] [c-instance1 c-shape2] component1]
                                          (thl/resolve-instance-and-main
                                           new-state
                                           (thp/id :instance1))

                                          file          (wsh/get-local-file new-state)
                                          component2    (ctkl/get-component file (thp/id :component1))

                                          saved-objects (:objects component2)]

                                      (t/is (= (:name instance1) "Rect 1"))
                                      (t/is (= (:name shape2) "Rect 1"))
                                      (t/is (= (:name c-instance1) "Rect 1"))
                                      (t/is (= (:name c-shape2) "Rect 1"))

                                      (t/is (some? component1))
                                      (t/is (some? component2))
                                      (t/is (nil? saved-objects)))))]

     (ptk/emit!
      store
      (dwl/delete-component {:id (thp/id :component1)})
      (dwl/restore-component thp/current-file-id (thp/id :component1))
      :the/end))))

(t/deftest test-instantiate-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)]))

         file         (wsh/get-local-file state)
         component-id (thp/id :component1)
         main1        (thp/get-shape state :main1)

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
                   (let [new-instance-id (-> new-state
                                             wsh/lookup-selected
                                             first)

                         [[instance1 shape2]
                          [c-instance1 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                          new-state
                          new-instance-id)]

                     (t/is (not= (:id main1) (:id instance1)))
                     (t/is (= (:id component) component-id))
                     (t/is (= (:name instance1) "Rect 1"))
                     (t/is (= (:name shape2) "Rect 1"))
                     (t/is (= (:name c-instance1) "Rect 1"))
                     (t/is (= (:name c-shape2) "Rect 1"))
                     (t/is (= (:component-file instance1)
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
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/move-to-library :lib1 "Library 1")
                   (thp/sample-page))

         library-id   (thp/id :lib1)
         component-id (thp/id :component1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]
                   ; Root Frame
                   ;   Rect 1              #--> <Library 1> Rect 1
                   ;     Rect 1            ---> <Library 1> Rect 1
                   ;
                   (let [new-instance-id (-> new-state
                                             wsh/lookup-selected
                                             first)

                         [[instance1 shape2]
                          [c-instance1 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                           new-state
                           new-instance-id)]

                     (t/is (= (:id component) component-id))
                     (t/is (= (:name instance1) "Rect 1"))
                     (t/is (= (:name shape2) "Rect 1"))
                     (t/is (= (:name c-instance1) "Rect 1"))
                     (t/is (= (:name c-shape2) "Rect 1"))
                     (t/is (= (:component-file instance1) library-id)))))]

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
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)))

         instance1 (thp/get-shape state :instance1)

         store (the/prepare-store state done
                (fn [new-state]
                  ; Expected shape tree:
                  ;
                  ; [Page]                                                                                                                                                                    
                  ; Root Frame                                                                                                                                                                
                  ;   Rect 1                                                                                                                                                                  
                  ;     Rect 1                                                                           
                  ;   Rect 1             
                  ;     Rect 1                                                                           
                  ;                                                                                      
                  ; [Rect 1]                                                                             
                  ;   page1 / Rect 1                                                                                                                                                          
                  ;
                  (let [[instance2 shape1]
                        (thl/resolve-noninstance
                         new-state
                         (:id instance1))]

                    (t/is (some? instance2))
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
                                      {:name "Rect 1"}))

          store (the/prepare-store state done
                  (fn [new-state]
                    ; Expected shape tree:
                    ;
                    ; [Page]                                                                               
                    ; Root Frame                                                                                                                                                                
                    ;   Group                                                                                                                                                                   
                    ;     Rect 1                                                                                                                                                                
                    ;       Rect 1                                                                                                                                                              
                    ;                                                                                      
                    ; [Rect 1]
                    ;   page1 / Rect 1                                                                     
                    ;                                                                                      
                    ; [Group]                                                                              
                    ;   page1 / Group                                                                                                                                                           
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

                      (t/is (= (:name group) "Board"))
                      (t/is (= (:name shape1) "Rect 1"))
                      (t/is (= (:name shape2) "Rect 1"))
                      (t/is (= (:name component) "Board"))
                      (t/is (= (:name c-group) "Board"))
                      (t/is (= (:name c-shape1) "Rect 1"))
                      (t/is (= (:name c-shape2) "Rect 1")))))]

      (ptk/emit!
        store
        (dw/select-shape (thp/id :shape1))
        (dwl/add-component)
        (dwsh/create-artboard-from-selection)
        (dwl/add-component)
        :the/end))))

(t/deftest test-instantiate-nested-component
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/make-component :main2 :component-2
                                       [(thp/id :main1)]))

         file         (wsh/get-local-file state)
         main1        (thp/get-shape state :main1)
         main2        (thp/get-shape state :main2)
         component-id (:component-id main2)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]                                                                                                                                                                    
                   ; Root Frame                                                                           
                   ;   Rect 1                                                                             
                   ;     Rect 1                                                                           
                   ;       Rect 1                                                                                                                                                              
                   ;   Rect 1              #--> Rect 1                                                                                                                                         
                   ;     Rect 1            @--> Rect 1                                                                                                                                         
                   ;       Rect 1          ---> Rect 1                                                                                                                                         
                   ;                                                                                      
                   ; [Rect 1]
                   ;   page1 / Rect 1                                                                     
                   ;                                                                                      
                   ; [Rect 1]                                                                             
                   ;   page1 / Rect 1                                                                                                                                                          
                   ;
                   (let [new-instance-id (-> new-state
                                             wsh/lookup-selected
                                             first)

                         [[instance1 shape1 shape2]
                          [c-instance1 c-shape1 c-shape2]
                          component]
                         (thl/resolve-instance-and-main
                           new-state
                           new-instance-id)]

                     ; TODO: get and check the instance inside component [Rect-2]

                     (t/is (not= (:id main1) (:id instance1)))
                     (t/is (= (:id component) component-id))
                     (t/is (= (:name instance1) "Rect 1"))
                     (t/is (= (:name shape1) "Rect 1"))
                     (t/is (= (:name shape2) "Rect 1"))
                     (t/is (= (:name c-instance1) "Rect 1"))
                     (t/is (= (:name c-shape1) "Rect 1"))
                     (t/is (= (:name c-shape2) "Rect 1")))))]

        (ptk/emit!
          store
          (dwl/instantiate-component (:id file)
                                     (:component-id main2)
                                     (gpt/point 100 100))
          :the/end))))

(t/deftest test-instantiate-nested-component-from-lib
  (t/async
   done
   (let [state (-> thp/initial-state
                   (thp/sample-page)
                   (thp/sample-shape :shape1 :rect
                                     {:name "Rect 1"})
                   (thp/make-component :main1 :component1
                                       [(thp/id :shape1)])
                   (thp/move-to-library :lib1 "Library 1")
                   (thp/sample-page)
                   (thp/instantiate-component :instance1
                                              (thp/id :component1)
                                              (thp/id :lib1)))

         file       (wsh/get-local-file state)
         library-id (thp/id :lib1)

         store (the/prepare-store state done
                 (fn [new-state]
                   ; Expected shape tree:
                   ;
                   ; [Page]                                                                               
                   ; Root Frame                                                                           
                   ;   Group                                                                              
                   ;     Rect 1            #--> <Library 1> Rect 1                                                                                                                             
                   ;       Rect 1          ---> <Library 1> Rect 1                                                                                                                             
                   ;                                                                                                                                                                           
                   ; [Group]                                                                                                                                                                   
                   ;   page1 / Group                                                                      
                   ;
                   (let [instance1 (thp/get-shape new-state :instance1)

                         [[group1 shape1 shape2] [c-group1 c-shape1 c-shape2] _component]
                         (thl/resolve-instance-and-main
                           new-state
                           (:parent-id instance1))]

                     (t/is (= (:name group1) "Board"))
                     (t/is (= (:name shape1) "Rect 1"))
                     (t/is (= (:name shape2) "Rect 1"))
                     (t/is (= (:name c-group1) "Board"))
                     (t/is (= (:name c-shape1) "Rect 1"))
                     (t/is (= (:name c-shape2) "Rect 1"))
                     (t/is (= (:component-file group1) thp/current-file-id))
                     (t/is (= (:component-file shape1) library-id))
                     (t/is (= (:component-file shape2) nil))
                     (t/is (= (:component-file c-group1) (:id file)))
                     (t/is (= (:component-file c-shape1) library-id))
                     (t/is (= (:component-file c-shape2) nil)))))]

        (ptk/emit!
          store
          (dw/select-shape (thp/id :instance1))
          (dwsh/create-artboard-from-selection)
          (dwl/add-component)
          :the/end))))
