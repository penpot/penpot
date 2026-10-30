;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns frontend-tests.logic.groups-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.groups :as dwgr]
   [app.main.data.workspace.selection :as dws]
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

(t/deftest mask-group-leaves-variant-main-in-the-set
  (t/async
    done
    (let [file (variant-file-with-rect)
          rect (cths/get-shape file :rect)
          main (cths/get-shape file :root1)
          comp (cthc/get-component file :comp1)]
      (run file
           [(dwgr/mask-group [(cthi/id :root1) (cthi/id :rect)])]
           done
           (fn [state]
             (let [file' (ths/get-file-from-state state)
                   main' (cths/get-shape file' :root1)
                   rect' (cths/get-shape file' :rect)
                   group (first (masked-groups file'))
                   comp' (cthc/get-component file' :comp1)]
               (t/is (= (:parent-id main) (:parent-id main')))
               (t/is (= (:variant-id main) (:variant-id main')))
               (t/is (= (:variant-name main) (:variant-name main')))
               (t/is (= (:variant-id comp) (:variant-id comp')))
               (t/is (= (:id group) (:parent-id rect')))
               (t/is (= (:parent-id rect) (:parent-id group)))
               (t/is (= (:selrect rect) (:selrect group)))))))))

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

(t/deftest group-shapes-leaves-variant-main-in-the-set
  (t/async
    done
    (let [file (variant-file-with-rect)
          rect (cths/get-shape file :rect)]
      (run file
           [(dwgr/group-shapes nil [(cthi/id :root1) (cthi/id :rect)])]
           done
           (fn [state]
             (let [file'  (ths/get-file-from-state state)
                   board  (cths/get-shape file' :variant)
                   main   (cths/get-shape file' :root1)
                   rect'  (cths/get-shape file' :rect)
                   group  (first (groups file'))]
               (t/is (= (:id board) (:parent-id main)))
               (t/is (some? group))
               (t/is (= (:id group) (:parent-id rect')))
               (t/is (= (:parent-id rect) (:parent-id group)))))))))

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
