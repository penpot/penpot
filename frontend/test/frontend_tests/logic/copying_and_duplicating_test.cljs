;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.copying-and-duplicating-test
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as dwp]
   [app.main.data.workspace.selection :as dws]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

;; Related .penpot file: common/test/cases/copying-and-duplicating.penpot
(defn- setup-file []
  (-> (cthf/sample-file :file1 :page-label :page-1)
      (ctho/add-simple-component :simple-1 :frame-simple-1 :rect-simple-1 :child-params {:type :rect :fills (cths/sample-fills-color :fill-color "#2152e5") :name "rect-simple-1"})

      (ctho/add-frame :frame-composed-1 :name "frame-composed-1")
      (cthc/instantiate-component :simple-1 :copy-simple-1 :parent-label :frame-composed-1 :children-labels [:composed-1-simple-1])
      (cths/add-sample-shape :rect-composed-1 :parent-label :frame-composed-1 :fills (cths/sample-fills-color :fill-color "#B1B2B5"))
      (cthc/make-component :composed-1 :frame-composed-1)

      (ctho/add-frame :frame-composed-2 :name "frame-composed-2")
      (cthc/instantiate-component :composed-1 :copy-composed-1-composed-2 :parent-label :frame-composed-2 :children-labels [:composed-1-composed-2])
      (cthc/make-component :composed-2 :frame-composed-2)

      (cthc/instantiate-component :composed-2 :copy-composed-2)

      (ctho/add-frame :frame-composed-3 :name "frame-composed-3")
      (ctho/add-group :group-3 :parent-label :frame-composed-3)
      (cthc/instantiate-component :composed-2 :copy-composed-1-composed-3 :parent-label :group-3 :children-labels [:composed-1-composed-2])
      (cths/add-sample-shape :circle-composed-3 :parent-label :group-3 :fills (cths/sample-fills-color :fill-color "#B1B2B5"))
      (cthc/make-component :composed-3 :frame-composed-3)

      (cthc/instantiate-component :composed-3 :copy-composed-3 :children-labels [:composed-2-composed-3])
      (cthf/add-sample-page :page-2)
      (cthf/switch-to-page :page-1)))


(defn- copy-paste-shape
  [id file & {:keys [target-page-label target-container-id]}]
  (let [features            #{"components/v2"}
        version             46
        page                (cthf/current-page file)
        target-page-id      (cthi/id target-page-label)
        shape               (if (keyword? id)
                              (cths/get-shape file id)
                              (cths/get-shape-by-id file id))
        pdata               (thp/simulate-copy-shape #{(:id shape)} (:objects page) {(:id  file) file} page file features version)
        target-container-id (or target-container-id (:parent-id shape))]

    (filter some?
            [(when target-page-id (dw/initialize-page (:id file) target-page-id))
             (dws/select-shape target-container-id)
             (dw/paste-shapes pdata)
             (when target-page-id (dw/initialize-page (:id file) (:id page)))])))

(defn- sync-file [file]
  (map (fn [component-tag]
         (->> component-tag
              (cthc/get-component file)
              :component-id
              (dwl/sync-file (:id file) (:id file) :components)))
       [:simple-1 :composed-1 :composed-2 :composed-3]))

(defn- set-color-bottom-shape [label file color]
  (let [shape (ctho/bottom-shape file label)]
    (concat
     [(dws/select-shape (:id shape))
      (dc/apply-color-from-palette color false)]
     (sync-file file))))

(defn- count-shapes [file name color]
  (let [page (cthf/current-page file)]
    (->> (vals (:objects page))
         (filter #(and
                   (= (:name %) name)
                   (-> (cths/get-shape-by-id file (:id %))
                       :fills
                       first
                       :fill-color
                       (= color))))
         (count))))

(defn- duplicate-each-main-and-first-level-copy [file]
  (concat (copy-paste-shape :frame-simple-1 file)
          (copy-paste-shape :frame-simple-1 file)
          (copy-paste-shape :frame-composed-1 file)
          (copy-paste-shape :frame-composed-1 file)
          (copy-paste-shape :frame-composed-2 file)
          (copy-paste-shape :frame-composed-2 file)
          (copy-paste-shape :frame-composed-3 file)
          (copy-paste-shape :frame-composed-3 file)
          (copy-paste-shape :copy-composed-2 file)
          (copy-paste-shape :copy-composed-2 file)
          (copy-paste-shape :copy-composed-3 file)
          (copy-paste-shape :copy-composed-3 file)))

(defn- duplicate-simple-nested-in-main-and-group [file]
  (concat (copy-paste-shape :copy-simple-1 file)
          (copy-paste-shape :copy-simple-1 file)
          (copy-paste-shape :group-3 file)
          (copy-paste-shape :group-3 file)))

(defn- duplicate-copy-nested-and-group-out-of-the-main
  [file & {:keys [target-page-label]}]
  (let [page (cthf/current-page file)
        frame-1-instance-ids (->> (vals (:objects page))
                                  (filter #(and
                                            (or
                                             (= (:name %) "Frame1")
                                             (= (:name %) "Group1"))
                                            (not (:component-root %))))
                                  (map :id))]
    (concat
     (apply concat
            (mapv #(copy-paste-shape % file :target-page-label target-page-label :target-container-id uuid/zero) frame-1-instance-ids))
     (apply concat
            (mapv #(copy-paste-shape % file :target-page-label target-page-label :target-container-id uuid/zero) frame-1-instance-ids)))))

(t/deftest main-and-first-level-copy-1
  (t/async done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
            ;; ==== Action


            ;; For each main and first level copy:
            ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 18)))))))))

(t/deftest main-and-first-level-copy-2
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
            ;; ==== Action


            ;; For each main and first level copy:
            ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
             ;; - Change color of the nearest main and check propagation to duplicated.
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 15)))))))))

(t/deftest main-and-first-level-copy-3
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
            ;; ==== Action


            ;; For each main and first level copy:
            ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
             ;; - Change color of the nearest main and check propagation to duplicated.
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :frame-composed-2 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 12)))))))))

(t/deftest main-and-first-level-copy-4
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
            ;; ==== Action


            ;; For each main and first level copy:
            ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
             ;; - Change color of the nearest main and check propagation to duplicated.
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :frame-composed-2 file {:color "#333333"})
             (set-color-bottom-shape :frame-composed-3 file {:color "#444444"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             (t/is (= (count-shapes file' "rect-simple-1" "#444444") 6)))))))))

(t/deftest copy-nested-in-main-1
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
            ;; - Duplicate it two times, keeping the duplicated inside the same main.
            events
            (concat
             (duplicate-simple-nested-in-main-and-group file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             ;; Check propagation to all copies.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 28)))))))))

(t/deftest copy-nested-in-main-2
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
            ;; - Duplicate it two times, keeping the duplicated inside the same main.
            events
            (concat
             (duplicate-simple-nested-in-main-and-group file)
             ;; - Change color of the nearest main
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             ;; Check propagation to duplicated.
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 9)))))))))

(t/deftest copy-nested-in-main-3
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
            ;; - Duplicate it two times, keeping the duplicated inside the same main.
            events
            (concat
             (duplicate-simple-nested-in-main-and-group file)
             ;; - Change color of the copy you duplicated from.
             (set-color-bottom-shape :group-3 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             ;; Check that it's NOT PROPAGATED.
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 2)))))))))

(t/deftest copy-nested-1
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main or other copy, and the group inside Composed3
            ;; main and copy:
            ;;   - Duplicate it two times, moving the duplicates out of the main.
            events
            (concat
             (duplicate-copy-nested-and-group-out-of-the-main file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             ;; Check propagation to all copies.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 20)))))))))

(t/deftest copy-nested-2
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main or other copy, and the group inside Composed3
            ;; main and copy:
            ;;   - Duplicate it two times, moving the duplicates out of the main.
            events
            (concat
             (duplicate-copy-nested-and-group-out-of-the-main file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
             ;; - Change color of the previous main
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :group-3 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
             (cthf/validate-file! file')
             ;; Check that it's NOT PROPAGATED.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 11))
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 7))
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 2)))))))))

(t/deftest copy-nested-3
  (t/async done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main or other copy, and the group inside Composed3
            ;; main and copy:
            ;;   - Duplicate it two times, moving the duplicates to another page
            events
            (concat
             (duplicate-copy-nested-and-group-out-of-the-main file :target-page-label :page-2)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
             ;; - Change color of the previous main
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :group-3 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (-> (ths/get-file-from-state new-state)
                           (cthf/switch-to-page :page-2))]
             (cthf/validate-file! file')
             ;; Check that it's NOT PROPAGATED.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 10))
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 4))
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 0)))))))))

(t/deftest duplicate-page-integrity-frame-group-component
  ;; This test covers the bug fixed in 2.9.0: duplicating a page with a mainInstance inside a group
  ;; must preserve parent/child referential integrity (parent-id and :shapes).
  ;;
  ;; Structure created:
  ;;   Page
  ;;     └─ Group ("group-1")
  ;;         └─ Main component ("frame-1")
  ;;             └─ Shape ("shape-1")
  ;;   The frame is also promoted to a component (main).
  ;;
  ;; The test checks:
  ;;   - The group, frame, and shape exist in the duplicated page.
  ;;   - The parent/child relationships are correct (group:shapes contains frame, frame:shapes contains shape, etc).
  ;;   - The duplicated page contains an instance of the component whose main is in the original page.
  (t/async done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [file (-> (cthf/sample-file :file1 :page-label :page-1)
                     (ctho/add-group :group-1 {:name "group-1"})
                     (ctho/add-frame :frame-1 :parent-label :group-1 {:name "frame-1"})
                     (cths/add-sample-shape :shape-1 :parent-label :frame-1 {:name "shape-1"})
                     (cthc/make-component :component-1 :frame-1))
            page-id (cthf/current-page-id file)
            store (ths/setup-store file)
            events [(dwp/duplicate-page page-id)]]
        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 pages-vec (get-in file' [:data :pages])
                 pages-index (get-in file' [:data :pages-index])
                 new-page-id (first (remove #(= page-id %) pages-vec))
                 new-page (get pages-index new-page-id)
                 new-objects (:objects new-page)
                 group (some #(when (= (:name %) "group-1") %) (vals new-objects))
                 frame (some #(when (= (:name %) "frame-1") %) (vals new-objects))
                 shape (some #(when (= (:name %) "shape-1") %) (vals new-objects))]

             (t/is group "Group exists in duplicated page")
             (t/is frame "Frame exists in duplicated page")
             (t/is shape "Shape exists in duplicated page")
             (t/is (some #(= (:id frame) %) (:shapes group)) "Group's :shapes contains frame's id")
             (t/is (some #(= (:id shape) %) (:shapes frame)) "Frame's :shapes contains shape's id")
             (t/is (= (:parent-id frame) (:id group)) "Frame's parent is group")
             (t/is (= (:parent-id shape) (:id frame)) "Shape's parent is frame")

             ;; Check the duplicated page must contain an instance of the component whose main is in the original page
             (let [original-page (get pages-index page-id)
                   original-main (some #(when (:component-root %) %) (vals (:objects original-page)))
                   instance (some #(when (:component-root %) %) (vals (:objects new-page)))
                   component-id (:component-id original-main)]
               (t/is (ctk/instance-of? instance (:id file) component-id)
                     (str "Duplicated page contains an instance of the original main component (component-id: " component-id ")")))

             (done))))))))

(defn- setup-swapped-copies-file
  "Creates a file with a component with two levels of nested copies inside. The component
   has one copy, and inside it, the topmost nested copy is swapped with a second component,
   also with one nested copy inside.

   {:frame-simple-1} [:name Frame1]                   # [Component :simple-1]                                                                                                
       :rect-simple-1 [:name Rect1]                                                                                                                                          

   {:frame-composed-1} [:name frame-composed-1]       # [Component :composed-1]                                                                                              
       :copy-simple-1-in-composed-1 [:name Frame1]    @--> frame-simple-1                                                                                                    
           <no-label #000894> [:name Rect1]           ---> rect-simple-1                                                                                                     

   {:frame-composed-2} [:name frame-composed-2]       # [Component :composed-2]                                                                                              
       :copy-composed-1-in-composed-2 [:name frame-composed-1] @--> frame-composed-1                                                                                         
           <no-label #000899> [:name Frame1]          @--> copy-simple-1-in-composed-1                                                                                       
               <no-label #00089a> [:name Rect1]       ---> <no-label #000894>                                                                                                

   {:frame-simple-2} [:name Frame1]                   # [Component :simple-2]                                                                                                
       :rect-simple-2 [:name Rect1]                                                                                                                                          

   {:frame-composed-3} [:name frame-composed-3]       # [Component :composed-3]                                                                                              
       :copy-simple-2-in-composed-3 [:name Frame1]    @--> frame-simple-2                                                                                                    
           <no-label #0008a4> [:name Rect1]           ---> rect-simple-2                                                                                                     

   :copy-composed-2 [:name frame-composed-2]          #--> [Component :composed-2] frame-composed-2                                                                          
       :swapped-composed-3 [:name frame-composed-3, :swap-slot-label :copy-composed-1-in-composed-2] @--> frame-composed-3                                                   
           :swapped-simple-2-frame [:name Frame1]          @--> copy-simple-2-in-composed-3                                                                                       
               :swapped-simple-2-rect [:name Rect1]       ---> <no-label #0008a4>
   "
  []
  (-> (cthf/sample-file :file1 :page-label :page-1)
      ;; 1. Simple component :simple-1
      (ctho/add-simple-component :simple-1 :frame-simple-1 :rect-simple-1)

      ;; 2. Composed component :composed-1 containing a copy of :simple-1
      (ctho/add-frame :frame-composed-1 :name "frame-composed-1")
      (cthc/instantiate-component :simple-1 :copy-simple-1-in-composed-1
                                  :parent-label :frame-composed-1)
      (cthc/make-component :composed-1 :frame-composed-1)

      ;; 3. Composed component :composed-2 containing a copy of :composed-1
      (ctho/add-frame :frame-composed-2 :name "frame-composed-2")
      (cthc/instantiate-component :composed-1 :copy-composed-1-in-composed-2
                                  :parent-label :frame-composed-2)
      (cthc/make-component :composed-2 :frame-composed-2)

      ;; 4. Simple component :simple-2
      (ctho/add-simple-component :simple-2 :frame-simple-2 :rect-simple-2)

      ;; 5. Composed component :composed-3 containing a copy of :simple-2
      (ctho/add-frame :frame-composed-3 :name "frame-composed-3")
      (cthc/instantiate-component :simple-2 :copy-simple-2-in-composed-3
                                  :parent-label :frame-composed-3)
      (cthc/make-component :composed-3 :frame-composed-3)

      ;; 6. A copy of :composed-2
      (cthc/instantiate-component :composed-2 :copy-composed-2
                                  :children-labels [:nested-copy-composed-1])

      ;; 7. Swap the nested copy of :composed-1 in the copy of :composed-2 with :composed-3
      (cthc/component-swap :nested-copy-composed-1 :composed-3 :swapped-composed-3
                           :children-labels [:swapped-simple-2-frame :swapped-simple-2-rect])))

(t/deftest duplicate-swapped-copies
  (t/async done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-swapped-copies-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; Copy to the clipboard all the shapes in the swapped copy one by one,
            ;; and paste them outside the copy, under uuid/zero
            events   (concat (copy-paste-shape :copy-composed-2 file :target-container-id uuid/zero)
                             (copy-paste-shape :swapped-composed-3 file :target-container-id uuid/zero)
                             (copy-paste-shape :swapped-simple-2-frame file :target-container-id uuid/zero)
                             (copy-paste-shape :swapped-simple-2-rect file :target-container-id uuid/zero))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)
                 page' (cthf/current-page file')]

             (cthf/validate-file! file')

             ;; ==== Check
             ;; Shape count breakdown (including page root):
             ;;  page root: 1
             ;;  :simple-1 main: 2 shapes (frame + rect)
             ;;  :composed-1 main: 3 shapes (frame + instance-of-simple-1 + its child)
             ;;  :composed-2 main: 4 shapes (frame + instance-of-composed-1 + its descendants)
             ;;  :simple-2 main: 2 shapes (frame + rect)
             ;;  :composed-3 main: 3 shapes (frame + instance-of-simple-2 + its child)
             ;;  copy of :composed-2 (with swapped child): 4 shapes
             ;;  pasted copy of :copy-composed-2: 4 shapes
             ;;  pasted copy of :composed-3: 3 shapes
             ;;  pasted copy of :swapped-simple-2-frame: 2 shapes
             ;;  pasted copy of :swapped-simple-2-rect: 1 shapes
             ;;  Total = 1 + 2 + 3 + 4 + 2 + 3 + 4 + 4 + 3 + 2 + 1 = 29
             (t/is (= (count (:objects page')) 29)))))))))

