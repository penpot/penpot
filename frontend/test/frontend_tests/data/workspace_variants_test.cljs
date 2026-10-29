;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-variants-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.variant :as cfv]
   [app.common.path-names :as cpn]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.variants :as dwv]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(defn- two-property-file-on-other-page
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02)
      (cthf/add-sample-page :page2)))

(defn- run
  [file events done assert-fn]
  (ths/run-store
   (ths/setup-store file)
   done
   events
   (fn [state]
     (cthf/validate-file! (ths/get-file-from-state state))
     (assert-fn state))))

(defn- variant-name
  [file label]
  (:variant-name (cths/get-shape file label :page-label :page1)))

(t/deftest update-property-value-from-another-page
  (t/async
    done
    (let [file         (two-property-file-on-other-page)
          component-id (:id (cthc/get-component file :c01))
          other-id     (cthi/id :page2)]
      (run file
           [(dwv/update-property-value component-id 0 "active")
            (dwv/update-property-value component-id 1 "crítica")]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   props (:variant-properties (cthc/get-component file :c01))]
               (t/is (= other-id (:current-page-id state)))
               (t/is (= "crítica" (-> props second :value)))
               (t/is (= "active, crítica" (variant-name file :m01)))
               (t/is (= (ctv/properties-to-name props) (variant-name file :m01)))))))))

(t/deftest update-property-value-on-main-page
  (t/async
    done
    (let [file         (-> (two-property-file-on-other-page)
                           (cthf/switch-to-page :page1))
          component-id (:id (cthc/get-component file :c01))]
      (run file
           [(dwv/update-property-value component-id 1 "crítica")]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   props (:variant-properties (cthc/get-component file :c01))]
               (t/is (= (cthi/id :page1) (:current-page-id state)))
               (t/is (= "crítica" (-> props second :value)))
               (t/is (= (ctv/properties-to-name props) (variant-name file :m01)))))))))

(t/deftest add-remove-reorder-from-another-page
  (t/async
    done
    (let [file       (two-property-file-on-other-page)
          variant-id (cthi/id :v01)]
      (run file
           [(dwv/add-new-property variant-id {:fill-values? true})
            (dwv/reorder-variant-poperties variant-id 0 3)
            (dwv/remove-property variant-id 0)]
           done
           (fn [state]
             (let [file   (ths/get-file-from-state state)
                   comp1  (cthc/get-component file :c01)
                   comp2  (cthc/get-component file :c02)
                   names1 (mapv :name (:variant-properties comp1))
                   names2 (mapv :name (:variant-properties comp2))]
               (t/is (= names1 names2))
               (t/is (= (ctv/properties-to-name (:variant-properties comp1))
                        (variant-name file :m01)))
               (t/is (= (ctv/properties-to-name (:variant-properties comp2))
                        (variant-name file :m02)))))))))

(t/deftest formula-rename-from-another-page
  (t/async
    done
    (let [file     (two-property-file-on-other-page)
          comp     (cthc/get-component file :c01)
          previous (:variant-properties comp)
          updated  [{:name "Property 1" :value "active"}
                    {:name "Property 2" :value "crítica"}]]
      (run file
           [(dwv/update-properties-names-and-values (:id comp) (:variant-id comp) previous updated)
            (dwv/remove-empty-properties (:variant-id comp))]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   props (:variant-properties (cthc/get-component file :c01))]
               (t/is (= "active" (-> props first :value)))
               (t/is (= "crítica" (-> props second :value)))
               (t/is (= (ctv/properties-to-name props) (variant-name file :m01)))))))))

(t/deftest update-property-name-from-another-page
  (t/async
    done
    (let [file       (two-property-file-on-other-page)
          variant-id (cthi/id :v01)]
      (run file
           [(dwv/update-property-name variant-id 0 "Status" {:trigger "test"})]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   names (mapv :name (:variant-properties (cthc/get-component file :c01)))]
               (t/is (= names (mapv :name (:variant-properties (cthc/get-component file :c02)))))
               (t/is (= "Status" (first names)))))))))

(t/deftest rename-variant-from-another-page
  (t/async
    done
    (let [file       (two-property-file-on-other-page)
          variant-id (cthi/id :v01)
          other-id   (cthi/id :page2)
          clean-name (cpn/clean-path "Renamed set")]
      (run file
           [(dwv/rename-variant variant-id "Renamed set")]
           done
           (fn [state]
             (let [file (ths/get-file-from-state state)
                   container (cths/get-shape file :v01 :page-label :page1)
                   main1     (cths/get-shape file :m01 :page-label :page1)
                   main2     (cths/get-shape file :m02 :page-label :page1)
                   comp1     (cthc/get-component file :c01)
                   comp2     (cthc/get-component file :c02)]
               (t/is (= other-id (:current-page-id state)))
               (t/is (= clean-name (:name container)))
               (t/is (= clean-name (:name main1)))
               (t/is (= clean-name (:name main2)))
               (t/is (= clean-name (cpn/merge-path-item (:path comp1) (:name comp1))))
               (t/is (= clean-name (cpn/merge-path-item (:path comp2) (:name comp2))))))))))

;; ---------------------------------------------------------------------------
;; Create-set / combine: every commit must be a valid file
;; ---------------------------------------------------------------------------

(def ^:private apply-local?
  (ptk/type? :app.main.data.changes/apply-changes-localy))

(defn- simple-main-file
  []
  (-> (cthf/sample-file :file1)
      (tho/add-simple-component :c01 :m01 :r01)))

(defn- two-mains-file
  []
  (-> (cthf/sample-file :file1)
      (tho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "Alpha" :x 0 :y 0 :width 100 :height 80})
      (tho/add-simple-component :c02 :m02 :r02
                                :root-params {:name "Beta" :x 150 :y 0 :width 100 :height 80})))

(defn- two-mains-on-page1-with-page2
  "Two mains on :page1; the open page is :page2 (Assets combine while elsewhere)."
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (tho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "Alpha" :x 0 :y 0 :width 100 :height 80})
      (tho/add-simple-component :c02 :m02 :r02
                                :root-params {:name "Beta" :x 150 :y 0 :width 100 :height 80})
      (cthf/add-sample-page :page2)
      (cthf/switch-to-page :page2)))

(defn- post-artboard-file
  "A normal frame holding one main — the state after create-artboard-from-shapes
   and before the container flag."
  []
  (-> (cthf/sample-file :file1)
      (tho/add-frame :v01 {:name "Set"})
      (tho/add-simple-component :c01 :m01 :r01
                                :root-params {:parent-label :v01 :name "Set"})))

(defn- page-objects
  [file]
  (:objects (cthf/current-page file)))

(defn- page-objects-on
  [file page-label]
  (:objects (cthf/get-page file page-label)))

(defn- commit-error
  [file]
  (try
    (cthf/validate-file! file)
    nil
    (catch :default cause
      (or (ex-message cause) (str cause)))))

(defn- watch-applied-commits
  "Record the file after every local apply. Keep the subscription for the
   whole run-store; assertions run in the completed callback."
  [store snapshots]
  (->> (ptk/input-stream store)
       (rx/filter apply-local?)
       (rx/subs! (fn [_]
                   (let [file (ths/get-file-from-state @store)]
                     (swap! snapshots conj {:file  file
                                            :error (commit-error file)}))))))

(defn- after-commits-idle
  "Stop once applied commits have been idle. Combine schedules an async
   undo commit after the main stream."
  [stream]
  (->> stream
       (rx/filter apply-local?)
       (rx/debounce 100)))

(defn- assert-valid-snapshots
  [snapshots]
  (doseq [{:keys [error]} snapshots]
    (t/is (nil? error) error)))

(defn- first-flagged
  [snapshots container-id]
  (first (filter #(ctk/is-variant-container?
                   (get (page-objects (:file %)) container-id))
                 snapshots)))

(t/deftest define-variant-container-commit-validates
  (let [file          (post-artboard-file)
        page          (cthf/current-page file)
        variant-id    (cthi/id :v01)
        main-id       (cthi/id :m01)
        component     (cthc/get-component file :c01)
        component-id  (:id component)
        name          (:name (cths/get-shape file :v01))
        changes       (-> (pcb/empty-changes nil (:id page))
                          (pcb/with-objects (:objects page))
                          (pcb/with-library-data (:data file))
                          (dwv/generate-define-variant-container
                           variant-id main-id component-id name))
        file'         (cthf/apply-changes file changes)
        container'    (cths/get-shape file' :v01)
        main'         (cths/get-shape file' :m01)
        comp'         (cthc/get-component file' :c01)]

    (t/is (ctk/is-variant-container? container'))
    (t/is (= [main-id] (:shapes container')))
    (t/is (= variant-id (:variant-id main')))
    (t/is (= variant-id (:variant-id comp')))
    (t/is (some? (:variant-id comp')))
    (t/is (= (ctv/properties-to-name (:variant-properties comp'))
             (:variant-name main'))))))

(t/deftest transform-in-variant-validates-after-every-commit
  (t/async
    done
    (let [file         (simple-main-file)
          main-id      (cthi/id :m01)
          variant-id   (uuid/next)
          snapshots    (atom [])
          store        (ths/setup-store file)]
      (watch-applied-commits store snapshots)
      (ths/run-store
       store
       done
       [(dwv/transform-in-variant main-id variant-id nil [] false true true)]
       (fn [state]
         (let [snaps      @snapshots
               flagged    (first-flagged snaps variant-id)
               file'      (ths/get-file-from-state state)
               objects'   (page-objects file')
               container' (get objects' variant-id)
               main'      (get objects' main-id)
               comp'      (cthc/get-component file' :c01)
               before-flag (take-while
                            #(not (ctk/is-variant-container?
                                   (get (page-objects (:file %)) variant-id)))
                            snaps)]
           (assert-valid-snapshots snaps)
           (t/is (some? flagged) "a commit must set :is-variant-container")
           (doseq [snap before-flag]
             (t/is (not (ctk/is-variant-container?
                         (get (page-objects (:file snap)) variant-id)))))
           (when flagged
             (let [ffile  (:file flagged)
                   fobjs  (page-objects ffile)
                   fcont  (get fobjs variant-id)
                   fmain  (get fobjs main-id)
                   fcomp  (cthc/get-component ffile :c01)]
               (t/is (= [main-id] (:shapes fcont)))
               (t/is (ctk/main-instance? fmain))
               (t/is (= variant-id (:variant-id fmain)))
               (t/is (= variant-id (:variant-id fcomp)))
               (t/is (= (ctv/properties-to-name (:variant-properties fcomp))
                        (:variant-name fmain)))))
           (t/is (ctk/is-variant-container? container'))
           (t/is (= variant-id (:variant-id main')))
           (t/is (= variant-id (:variant-id comp')))))
       after-commits-idle))))

(t/deftest add-new-variant-on-existing-set-still-validates
  (t/async
    done
    (let [file       (-> (two-property-file-on-other-page)
                         (cthf/switch-to-page :page1))
          main-id    (cthi/id :m01)
          c01-before (cthc/get-component file :c01)
          c02-before (cthc/get-component file :c02)
          last-c01   (-> c01-before :variant-properties last)
          last-c02   (-> c02-before :variant-properties last)]
      (run file
           [(dwv/add-new-variant main-id)]
           done
           (fn [state]
             (let [file'      (ths/get-file-from-state state)
                   container' (cths/get-shape file' :v01 :page-label :page1)
                   c01'       (cthc/get-component file' :c01)
                   c02'       (cthc/get-component file' :c02)
                   new-id     (first (:shapes container'))
                   new-shape  (cths/get-shape-by-id file' new-id :page-label :page1)
                   new-comp   (cthc/get-component-by-id file' (:component-id new-shape))]
               (t/is (= 3 (count (:shapes container'))))
               (t/is (= last-c01 (-> c01' :variant-properties last)))
               (t/is (= last-c02 (-> c02' :variant-properties last)))
               (t/is (not= last-c01 (-> new-comp :variant-properties last)))
               (t/is (= (ctv/properties-to-name (:variant-properties new-comp))
                        (:variant-name new-shape)))))))))

(t/deftest combine-as-variants-validates-after-every-commit
  (t/async
    done
    (let [file       (two-mains-file)
          id1        (cthi/id :m01)
          id2        (cthi/id :m02)
          variant-id (uuid/next)
          snapshots  (atom [])
          store      (ths/setup-store file)]
      (watch-applied-commits store snapshots)
      (ths/run-store
       store
       done
       [(dwv/combine-as-variants [id1 id2] {:variant-id variant-id :trigger "test"})]
       (fn [state]
         (let [snaps   @snapshots
               file'   (ths/get-file-from-state state)
               objects (page-objects file')
               container (get objects variant-id)
               m01'    (get objects id1)
               m02'    (get objects id2)
               c01'    (cthc/get-component file' :c01)
               c02'    (cthc/get-component file' :c02)
               names1  (mapv :name (:variant-properties c01'))
               names2  (mapv :name (:variant-properties c02'))
               ordered (mapv :main-instance-id
                             (cfv/find-variant-components (:data file') objects variant-id))]
           (assert-valid-snapshots snaps)
           (doseq [{:keys [file]} snaps]
             (let [objs  (page-objects file)
                   cont  (get objs variant-id)
                   kids  (set (:shapes cont))]
               (when (contains? kids id2)
                 (t/is (ctk/is-variant-container? cont)
                       "other mains must not enter the frame before it is a variant container"))))
           (t/is (ctk/is-variant-container? container))
           (t/is (= names1 names2))
           (t/is (= [id1 id2] ordered))
           (t/is (= variant-id (:variant-id m01')))
           (t/is (= variant-id (:variant-id m02')))
           (t/is (= variant-id (:parent-id m01')))
           (t/is (= variant-id (:parent-id m02')))))
       after-commits-idle))))

(t/deftest combine-as-variants-from-another-page-validates-after-every-commit
  (t/async
    done
    (let [file       (two-mains-on-page1-with-page2)
          id1        (cthi/id :m01)
          id2        (cthi/id :m02)
          page1      (cthi/id :page1)
          variant-id (uuid/next)
          snapshots  (atom [])
          store      (ths/setup-store file)]
      (watch-applied-commits store snapshots)
      (ths/run-store
       store
       done
       [(dwv/combine-as-variants [id1 id2] {:page-id page1 :variant-id variant-id :trigger "test"})
        (dw/initialize-page (:id file) page1)]
       (fn [state]
         (let [snaps     @snapshots
               file'     (ths/get-file-from-state state)
               objects   (page-objects-on file' :page1)
               container (get objects variant-id)
               m01'      (get objects id1)
               m02'      (get objects id2)
               c01'      (cthc/get-component file' :c01)
               c02'      (cthc/get-component file' :c02)]
           (assert-valid-snapshots snaps)
           (t/is (ctk/is-variant-container? container))
           (t/is (= variant-id (:variant-id m01')))
           (t/is (= variant-id (:variant-id m02')))
           (t/is (= variant-id (:variant-id c01')))
           (t/is (= variant-id (:variant-id c02')))))
       after-commits-idle))))
