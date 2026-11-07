;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns frontend-tests.logic.groups-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.groups :as dwgr]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (cthi/reset-idmap!))})

(defn- variant-file
  []
  (-> (cthf/sample-file :file1)
      (thv/add-variant :variant :comp1 :root1 :comp2 :root2
                       :variant1-params {:name "Button"}
                       :variant2-params {:name "Button"})
      (cths/update-shape :variant :name "Button")))

(defn- variant-file-with-rect
  []
  (cths/add-sample-shape (variant-file) :rect))

(defn- variant-file-with-nested-and-outside
  []
  (-> (variant-file)
      (cths/add-sample-shape :nested :parent-label :root1)
      (cths/add-sample-shape :outside)))

(defn- variant-file-with-two-nested
  []
  (-> (variant-file)
      (cths/add-sample-shape :nested-a :parent-label :root1)
      (cths/add-sample-shape :nested-b :parent-label :root1)))

(defn- variant-file-with-nested-in-each-main
  []
  (-> (variant-file)
      (cths/add-sample-shape :nested-a :parent-label :root1)
      (cths/add-sample-shape :nested-b :parent-label :root2)))

(defn- masked-groups
  [file]
  (->> (cthf/current-page file)
       :objects
       vals
       (filter :masked-group)))

(defn- groups
  [file]
  (->> (cthf/current-page file)
       :objects
       vals
       (filter #(= :group (:type %)))))

(defn- run
  "Run workspace events, then validate the file before assertions.

   Pass `:commits` as an atom to collect each `dch/commit?` event."
  [file events done assert-fn & {:keys [commits]}]
  (let [store (ths/setup-store file)]
    (when commits
      (->> (ptk/input-stream store)
           (rx/filter dch/commit?)
           (rx/subs! #(swap! commits conj %))))
    (ths/run-store
     store
     done
     events
     (fn [state]
       (cthf/validate-file! (ths/get-file-from-state state))
       (assert-fn state)))))


(t/deftest test-create-group
  (t/async
    done
    (let [;; ==== Setup
          file       (-> (cthf/sample-file :file1)
                         (cths/add-sample-shape :test-shape))
          store      (ths/setup-store file)
          test-shape (cths/get-shape file :test-shape)

          ;; ==== Action
          events
          [(dws/select-shapes (d/ordered-set (:id test-shape)))
           (dwgr/group-selected)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               group-id      (->> (:objects page')
                                  vals
                                  (filter #(= :group (:type %)))
                                  first
                                  :id)]
           ;; ==== Check
           ;; Group has been created and is selected
           (t/is (= (get-in new-state [:workspace-local :selected]) #{group-id}))))))))

(t/deftest mask-group-of-main-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-rect)
          rect    (cths/get-shape file :rect)
          main    (cths/get-shape file :root1)
          commits (atom [])]
      (run file
           [(dwgr/mask-group [(cthi/id :root1) (cthi/id :rect)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (masked-groups file')))
               (t/is (= (:parent-id main) (:parent-id (cths/get-shape file' :root1))))
               (t/is (= (:parent-id rect) (:parent-id (cths/get-shape file' :rect))))))
           :commits commits))))

(t/deftest mask-group-of-nested-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-nested-and-outside)
          nested  (cths/get-shape file :nested)
          outside (cths/get-shape file :outside)
          commits (atom [])]
      (t/is (ctn/crosses-variant-set-boundary?
             (:objects (cthf/current-page file))
             [nested outside]))
      (run file
           [(dwgr/mask-group [(cthi/id :nested) (cthi/id :outside)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (masked-groups file')))
               (t/is (= (:parent-id nested)
                        (:parent-id (cths/get-shape file' :nested))))
               (t/is (= (:parent-id outside)
                        (:parent-id (cths/get-shape file' :outside))))))
           :commits commits))))

(t/deftest mask-group-of-only-variant-mains-does-not-commit
  (t/async
    done
    (let [file    (variant-file)
          commits (atom [])]
      (run file
           [(dwgr/mask-group [(cthi/id :root1) (cthi/id :root2)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   board (cths/get-shape file' :variant)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (masked-groups file')))
               (t/is (= (:id board) (:parent-id (cths/get-shape file' :root1))))
               (t/is (= (:id board) (:parent-id (cths/get-shape file' :root2))))))
           :commits commits))))

(t/deftest mask-group-of-ordinary-shapes-creates-a-mask
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (cths/add-sample-shape :a)
                   (cths/add-sample-shape :b))]
      (run file
           [(dwgr/mask-group [(cthi/id :a) (cthi/id :b)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   group (first (masked-groups file'))]
               (t/is (= 1 (count (masked-groups file'))))
               (t/is (:masked-group group))
               (t/is (= (:id group) (:parent-id (cths/get-shape file' :a))))
               (t/is (= (:id group) (:parent-id (cths/get-shape file' :b))))))))))

(t/deftest mask-group-marks-a-single-plain-group
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (cths/add-sample-shape :g :type :group)
                   (cths/add-sample-shape :child :parent-label :g))
          group-id (cthi/id :g)]
      (run file
           [(dwgr/mask-group [group-id])]
           done
           (fn [state]
             (let [file'  (ths/get-file-from-state state)
                   group' (cths/get-shape file' :g)]
               (t/is (:masked-group group'))
               (t/is (= [group-id] (map :id (groups file'))))
               (t/is (= group-id (:parent-id (cths/get-shape file' :child))))))))))

(t/deftest group-shapes-of-main-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-rect)
          rect    (cths/get-shape file :rect)
          main    (cths/get-shape file :root1)
          commits (atom [])]
      (run file
           [(dwgr/group-shapes nil [(cthi/id :root1) (cthi/id :rect)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (groups file')))
               (t/is (= (:parent-id main) (:parent-id (cths/get-shape file' :root1))))
               (t/is (= (:parent-id rect) (:parent-id (cths/get-shape file' :rect))))))
           :commits commits))))

(t/deftest group-shapes-of-nested-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-nested-and-outside)
          nested  (cths/get-shape file :nested)
          outside (cths/get-shape file :outside)
          commits (atom [])]
      (run file
           [(dwgr/group-shapes nil [(cthi/id :nested) (cthi/id :outside)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (groups file')))
               (t/is (= (:parent-id nested)
                        (:parent-id (cths/get-shape file' :nested))))
               (t/is (= (:parent-id outside)
                        (:parent-id (cths/get-shape file' :outside))))))
           :commits commits))))

(t/deftest group-shapes-of-two-nested-children-creates-a-group
  (t/async
    done
    (let [file (variant-file-with-two-nested)
          main (cths/get-shape file :root1)]
      (run file
           [(dwgr/group-shapes nil [(cthi/id :nested-a) (cthi/id :nested-b)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   group (first (groups file'))]
               (t/is (some? group))
               (t/is (= (:id main) (:parent-id group)))
               (t/is (= (:id group) (:parent-id (cths/get-shape file' :nested-a))))
               (t/is (= (:id group) (:parent-id (cths/get-shape file' :nested-b))))))))))

(t/deftest group-shapes-of-main-and-nested-keeps-main-groups-nested
  (t/async
    done
    (let [file   (-> (variant-file)
                     (cths/add-sample-shape :nested :parent-label :root1))
          main   (cths/get-shape file :root1)
          nested (cths/get-shape file :nested)
          commits (atom [])]
      (run file
           [(dwgr/group-shapes nil [(cthi/id :root1) (cthi/id :nested)])]
           done
           (fn [state]
             (let [file'   (ths/get-file-from-state state)
                   main'   (cths/get-shape file' :root1)
                   nested' (cths/get-shape file' :nested)
                   group   (first (groups file'))]
               (t/is (pos? (count @commits)))
               (t/is (some? group))
               (t/is (= (:parent-id main) (:parent-id main')))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:id main') (:parent-id group)))
               (t/is (= (:id group) (:parent-id nested')))
               (t/is (= (:parent-id nested) (:id main)))))
           :commits commits))))

(t/deftest mask-group-of-main-and-nested-keeps-main-masks-nested
  (t/async
    done
    (let [file    (-> (variant-file)
                      (cths/add-sample-shape :nested :parent-label :root1))
          main    (cths/get-shape file :root1)
          nested  (cths/get-shape file :nested)
          commits (atom [])]
      (run file
           [(dwgr/mask-group [(cthi/id :root1) (cthi/id :nested)])]
           done
           (fn [state]
             (let [file'   (ths/get-file-from-state state)
                   main'   (cths/get-shape file' :root1)
                   nested' (cths/get-shape file' :nested)
                   group   (first (masked-groups file'))]
               (t/is (pos? (count @commits)))
               (t/is (some? group))
               (t/is (:masked-group group))
               (t/is (= (:parent-id main) (:parent-id main')))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:id main') (:parent-id group)))
               (t/is (= (:id group) (:parent-id nested')))
               (t/is (= (:parent-id nested) (:id main)))))
           :commits commits))))

(t/deftest group-shapes-of-nested-in-different-variants-does-not-commit
  (t/async
    done
    (let [file     (variant-file-with-nested-in-each-main)
          nested-a (cths/get-shape file :nested-a)
          nested-b (cths/get-shape file :nested-b)
          commits  (atom [])]
      (t/is (ctn/crosses-variant-set-boundary?
             (:objects (cthf/current-page file))
             [nested-a nested-b]))
      (run file
           [(dwgr/group-shapes nil [(cthi/id :nested-a) (cthi/id :nested-b)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (groups file')))
               (t/is (= (:parent-id nested-a)
                        (:parent-id (cths/get-shape file' :nested-a))))
               (t/is (= (:parent-id nested-b)
                        (:parent-id (cths/get-shape file' :nested-b))))))
           :commits commits))))

(t/deftest mask-group-of-nested-in-different-variants-does-not-commit
  (t/async
    done
    (let [file     (variant-file-with-nested-in-each-main)
          nested-a (cths/get-shape file :nested-a)
          nested-b (cths/get-shape file :nested-b)
          commits  (atom [])]
      (run file
           [(dwgr/mask-group [(cthi/id :nested-a) (cthi/id :nested-b)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (masked-groups file')))
               (t/is (= (:parent-id nested-a)
                        (:parent-id (cths/get-shape file' :nested-a))))
               (t/is (= (:parent-id nested-b)
                        (:parent-id (cths/get-shape file' :nested-b))))))
           :commits commits))))

(t/deftest create-artboard-of-nested-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-nested-and-outside)
          nested  (cths/get-shape file :nested)
          outside (cths/get-shape file :outside)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :nested) (cthi/id :outside)))
            (dwsh/create-artboard-from-selection)]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (= (:parent-id nested)
                        (:parent-id (cths/get-shape file' :nested))))
               (t/is (= (:parent-id outside)
                        (:parent-id (cths/get-shape file' :outside))))))
           :commits commits))))

(t/deftest create-artboard-of-main-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-rect)
          rect    (cths/get-shape file :rect)
          main    (cths/get-shape file :root1)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :root1) (cthi/id :rect)))
            (dwsh/create-artboard-from-selection)]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (= (:parent-id main) (:parent-id (cths/get-shape file' :root1))))
               (t/is (= (:parent-id rect) (:parent-id (cths/get-shape file' :rect))))))
           :commits commits))))

(t/deftest create-artboard-of-two-nested-children-commits
  (t/async
    done
    (let [file    (variant-file-with-two-nested)
          main    (cths/get-shape file :root1)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :nested-a) (cthi/id :nested-b)))
            (dwsh/create-artboard-from-selection)]
           done
           (fn [state]
             (let [file'     (ths/get-file-from-state state)
                   nested-a' (cths/get-shape file' :nested-a)
                   nested-b' (cths/get-shape file' :nested-b)
                   board     (get (:objects (cthf/current-page file'))
                                  (:parent-id nested-a'))]
               (t/is (pos? (count @commits)))
               (t/is (= :frame (:type board)))
               (t/is (not (ctk/is-variant-container? board)))
               (t/is (= (:id main) (:parent-id board)))
               (t/is (= (:id board) (:parent-id nested-a')))
               (t/is (= (:id board) (:parent-id nested-b')))))
           :commits commits))))

(t/deftest create-artboard-of-main-and-nested-keeps-main-boards-nested
  (t/async
    done
    (let [file    (-> (variant-file)
                      (cths/add-sample-shape :nested :parent-label :root1))
          main    (cths/get-shape file :root1)
          nested  (cths/get-shape file :nested)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :root1) (cthi/id :nested)))
            (dwsh/create-artboard-from-selection)]
           done
           (fn [state]
             (let [file'   (ths/get-file-from-state state)
                   main'   (cths/get-shape file' :root1)
                   nested' (cths/get-shape file' :nested)
                   board   (get (:objects (cthf/current-page file'))
                                (:parent-id nested'))]
               (t/is (pos? (count @commits)))
               (t/is (= :frame (:type board)))
               (t/is (not (ctk/is-variant-container? board)))
               (t/is (= (:parent-id main) (:parent-id main')))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:id main') (:parent-id board)))
               (t/is (= (:id board) (:parent-id nested')))
               (t/is (= (:parent-id nested) (:id main)))))
           :commits commits))))

(t/deftest group-selected-of-main-and-nested-keeps-main-groups-nested
  (t/async
    done
    (let [file    (-> (variant-file)
                      (cths/add-sample-shape :nested :parent-label :root1))
          main    (cths/get-shape file :root1)
          nested  (cths/get-shape file :nested)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :root1) (cthi/id :nested)))
            (dwgr/group-selected)]
           done
           (fn [state]
             (let [file'   (ths/get-file-from-state state)
                   main'   (cths/get-shape file' :root1)
                   nested' (cths/get-shape file' :nested)
                   group   (first (groups file'))]
               (t/is (pos? (count @commits)))
               (t/is (some? group))
               (t/is (= (:parent-id main) (:parent-id main')))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:id main') (:parent-id group)))
               (t/is (= (:id group) (:parent-id nested')))
               (t/is (= (:parent-id nested) (:id main)))))
           :commits commits))))

(t/deftest mask-group-from-selection-of-main-and-nested-keeps-main-masks-nested
  (t/async
    done
    (let [file    (-> (variant-file)
                      (cths/add-sample-shape :nested :parent-label :root1))
          main    (cths/get-shape file :root1)
          nested  (cths/get-shape file :nested)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :root1) (cthi/id :nested)))
            (dwgr/mask-group)]
           done
           (fn [state]
             (let [file'   (ths/get-file-from-state state)
                   main'   (cths/get-shape file' :root1)
                   nested' (cths/get-shape file' :nested)
                   group   (first (masked-groups file'))]
               (t/is (pos? (count @commits)))
               (t/is (some? group))
               (t/is (:masked-group group))
               (t/is (= (:parent-id main) (:parent-id main')))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:id main') (:parent-id group)))
               (t/is (= (:id group) (:parent-id nested')))
               (t/is (= (:parent-id nested) (:id main)))))
           :commits commits))))

(t/deftest create-layout-of-nested-and-outside-does-not-commit
  (t/async
    done
    (let [file    (variant-file-with-nested-and-outside)
          nested  (cths/get-shape file :nested)
          outside (cths/get-shape file :outside)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :nested) (cthi/id :outside)))
            (dwsl/create-layout-from-selection :flex)]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (= (:parent-id nested)
                        (:parent-id (cths/get-shape file' :nested))))
               (t/is (= (:parent-id outside)
                        (:parent-id (cths/get-shape file' :outside))))))
           :commits commits))))

(t/deftest create-layout-of-nested-in-different-variants-does-not-commit
  (t/async
    done
    (let [file     (variant-file-with-nested-in-each-main)
          nested-a (cths/get-shape file :nested-a)
          nested-b (cths/get-shape file :nested-b)
          commits  (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :nested-a) (cthi/id :nested-b)))
            (dwsl/create-layout :flex)]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (= (:parent-id nested-a)
                        (:parent-id (cths/get-shape file' :nested-a))))
               (t/is (= (:parent-id nested-b)
                        (:parent-id (cths/get-shape file' :nested-b))))))
           :commits commits))))

(t/deftest group-shapes-of-variant-container-only-does-not-commit
  (t/async
    done
    (let [file    (variant-file)
          board   (cths/get-shape file :variant)
          commits (atom [])]
      (run file
           [(dwgr/group-shapes nil [(cthi/id :variant)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (groups file')))
               (t/is (= (:parent-id board)
                        (:parent-id (cths/get-shape file' :variant))))
               (t/is (ctk/is-variant-container?
                      (cths/get-shape file' :variant)))))
           :commits commits))))

(t/deftest mask-group-of-variant-container-only-does-not-commit
  (t/async
    done
    (let [file    (variant-file)
          board   (cths/get-shape file :variant)
          commits (atom [])]
      (run file
           [(dwgr/mask-group [(cthi/id :variant)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (empty? (masked-groups file')))
               (t/is (= (:parent-id board)
                        (:parent-id (cths/get-shape file' :variant))))
               (t/is (ctk/is-variant-container?
                      (cths/get-shape file' :variant)))))
           :commits commits))))

(t/deftest create-artboard-of-variant-container-only-does-not-commit
  (t/async
    done
    (let [file    (variant-file)
          board   (cths/get-shape file :variant)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :variant)))
            (dwsh/create-artboard-from-selection)]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)]
               (t/is (= 0 (count @commits)))
               (t/is (= (:parent-id board)
                        (:parent-id (cths/get-shape file' :variant))))
               (t/is (ctk/is-variant-container?
                      (cths/get-shape file' :variant)))))
           :commits commits))))

(t/deftest mask-group-of-two-nested-children-creates-a-mask
  (t/async
    done
    (let [file (variant-file-with-two-nested)
          main (cths/get-shape file :root1)]
      (run file
           [(dwgr/mask-group [(cthi/id :nested-a) (cthi/id :nested-b)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   group (first (masked-groups file'))]
               (t/is (= 1 (count (masked-groups file'))))
               (t/is (:masked-group group))
               (t/is (= (:id main) (:parent-id group)))
               (t/is (= (:id group) (:parent-id (cths/get-shape file' :nested-a))))
               (t/is (= (:id group) (:parent-id (cths/get-shape file' :nested-b))))))))))

(t/deftest create-layout-of-variant-container-only-does-not-commit
  (t/async
    done
    (let [file    (variant-file)
          board   (cths/get-shape file :variant)
          commits (atom [])]
      (t/is (not (ctl/any-layout? board)))
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :variant)))
            ;; Pin the create-layout entry (from-id hole), not only
            ;; create-layout-from-selection which already no-ops.
            (dwsl/create-layout :flex)]
           done
           (fn [state]
             (let [file'  (ths/get-file-from-state state)
                   board' (cths/get-shape file' :variant)]
               (t/is (= 0 (count @commits)))
               (t/is (not (ctl/any-layout? board')))
               (t/is (ctk/is-variant-container? board'))
               (t/is (= (:parent-id board) (:parent-id board')))))
           :commits commits))))

(t/deftest create-layout-of-variant-main-commits
  (t/async
    done
    (let [file    (variant-file)
          main    (cths/get-shape file :root1)
          board   (cths/get-shape file :variant)
          commits (atom [])]
      (t/is (not (ctl/any-layout? main)))
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :root1)))
            ;; Locks create-layout → create-layout-from-id for a single main.
            (dwsl/create-layout :flex)]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   main' (cths/get-shape file' :root1)]
               (t/is (pos? (count @commits)))
               (t/is (ctl/flex-layout? main'))
               (t/is (ctk/is-variant? main'))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:id board) (:parent-id main')))))
           :commits commits))))

(t/deftest create-layout-of-one-nested-child-commits
  (t/async
    done
    (let [file    (-> (variant-file)
                      (cths/add-sample-shape :nested :parent-label :root1))
          nested  (cths/get-shape file :nested)
          main    (cths/get-shape file :root1)
          commits (atom [])]
      (run file
           [(dws/select-shapes (d/ordered-set (cthi/id :nested)))
            ;; Same Shift+A / menu entry as the container refuse and main allow.
            (dwsl/create-layout :flex)]
           done
           (fn [state]
             (let [file'   (ths/get-file-from-state state)
                   nested' (cths/get-shape file' :nested)
                   ;; Layout wraps the child in a new board under the main.
                   parent' (get (:objects (cthf/current-page file'))
                                (:parent-id nested'))]
               (t/is (pos? (count @commits)))
               (t/is (not= (:parent-id nested) (:parent-id nested')))
               (t/is (= (:id main) (:parent-id parent')))
               (t/is (ctl/any-layout? parent'))))
           :commits commits))))

(t/deftest ungroup-shapes-leaves-a-variant-container
  (t/async
    done
    (let [file (variant-file)]
      (run file
           [(dwgr/ungroup-shapes [(cthi/id :variant)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   board (cths/get-shape file' :variant)]
               (t/is (ctk/is-variant-container? board))
               (t/is (= (:id board) (:parent-id (cths/get-shape file' :root1))))
               (t/is (= (:id board) (:parent-id (cths/get-shape file' :root2))))))))))
