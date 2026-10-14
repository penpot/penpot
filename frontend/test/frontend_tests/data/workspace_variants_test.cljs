;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-variants-test
  "The checks the workspace runs before combining components as variants
   and before a variant switch, and the branches of the variant events that
   the composable cases (`frontend-tests.composable-tests.comp.sync-test`)
   do not reach: a combine on another page, a switch that would nest a
   component in itself, the shortcuts with no variant selected and the
   rename of a plain component."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.variant :as cfv]
   [app.common.logic.variants :as clv]
   [app.common.path-names :as cpn]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as cthv]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- two-property-file-on-other-page
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (cthv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02)
      (cthf/add-sample-page :page2)))

(defn- nine-property-file-with-empty-middle
  "Nine properties; Property 5 is empty on every variant."
  []
  (let [names  (mapv #(str "Property " %) (range 1 10))
        props1 (mapv (fn [n i]
                       {:name n :value (if (= i 4) "" (str "a" (inc i)))})
                     names
                     (range))
        props2 (mapv (fn [n i]
                       {:name n :value (if (= i 4) "" (str "b" (inc i)))})
                     names
                     (range))]
    (-> (cthf/sample-file :file1 :page-label :page1)
        (cthv/add-variant :v01 :c01 :m01 :c02 :m02)
        (cthc/update-component :c01 {:variant-properties props1})
        (cthc/update-component :c02 {:variant-properties props2})
        (cths/update-shape :m01 :variant-name (ctv/properties-to-name props1))
        (cths/update-shape :m02 :variant-name (ctv/properties-to-name props2))
        (cthf/add-sample-page :page2))))

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

(t/deftest remove-empty-properties-preserves-order-past-eight
  (t/async
    done
    (let [file       (nine-property-file-with-empty-middle)
          variant-id (cthi/id :v01)
          expected   ["Property 1" "Property 2" "Property 3" "Property 4"
                      "Property 6" "Property 7" "Property 8" "Property 9"]]
      (run file
           [(dwv/remove-empty-properties variant-id)]
           done
           (fn [state]
             (let [file   (ths/get-file-from-state state)
                   names1 (mapv :name (:variant-properties (cthc/get-component file :c01)))
                   names2 (mapv :name (:variant-properties (cthc/get-component file :c02)))]
               (t/is (= expected names1))
               (t/is (= expected names2))
               (t/is (not-any? #(= "Property 5" %) names1))))))))

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
      (ctho/add-simple-component :c01 :m01 :r01)))

(defn- two-mains-file
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "Alpha" :x 0 :y 0 :width 100 :height 80})
      (ctho/add-simple-component :c02 :m02 :r02
                                :root-params {:name "Beta" :x 150 :y 0 :width 100 :height 80})))

(defn- two-mains-shared-prefix-file
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "UI / Alpha" :x 0 :y 0 :width 100 :height 80})
      (ctho/add-simple-component :c02 :m02 :r02
                                :root-params {:name "UI / Beta" :x 150 :y 0 :width 100 :height 80})))

(defn- three-same-name-mains-file
  []
  (-> (cthf/sample-file :file1)
      (tho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "Tag" :x 0 :y 0 :width 100 :height 80})
      (tho/add-simple-component :c02 :m02 :r02
                                :root-params {:name "Tag" :x 150 :y 0 :width 100 :height 80})
      (tho/add-simple-component :c03 :m03 :r03
                                :root-params {:name "Tag" :x 300 :y 0 :width 100 :height 80})))

(defn- path-named-main-file
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "Group / Button"})))

(defn- two-mains-on-page1-with-page2
  "Two mains on :page1; the open page is :page2 (Assets combine while elsewhere)."
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (ctho/add-simple-component :c01 :m01 :r01
                                :root-params {:name "Alpha" :x 0 :y 0 :width 100 :height 80})
      (ctho/add-simple-component :c02 :m02 :r02
                                :root-params {:name "Beta" :x 150 :y 0 :width 100 :height 80})
      (cthf/add-sample-page :page2)
      (cthf/switch-to-page :page2)))

(defn- post-artboard-file
  "A normal frame holding one main — the state after create-artboard-from-shapes
   and before the container flag."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :v01 {:name "Set"})
      (ctho/add-simple-component :c01 :m01 :r01
                                :root-params {:parent-label :v01 :name "Set"})))

(defn- component-full-name
  [component]
  (cpn/merge-path-item (:path component) (:name component)))

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
                          (clv/generate-define-variant-container
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
             (:variant-name main')))))

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

(t/deftest transform-in-variant-path-name-validates-after-every-commit
  (t/async
    done
    (let [file       (path-named-main-file)
          main-id    (cthi/id :m01)
          variant-id (uuid/next)
          snapshots  (atom [])
          store      (ths/setup-store file)]
      (watch-applied-commits store snapshots)
      (ths/run-store
       store
       done
       [(dwv/transform-in-variant main-id variant-id nil [] false true true)]
       (fn [state]
         (let [snaps       @snapshots
               flagged     (first-flagged snaps variant-id)
               file'       (ths/get-file-from-state state)
               objects'    (page-objects file')
               container'  (get objects' variant-id)
               main'       (get objects' main-id)
               comp'       (cthc/get-component file' :c01)
               before-flag (take-while
                            #(not (ctk/is-variant-container?
                                   (get (page-objects (:file %)) variant-id)))
                            snaps)]
           (assert-valid-snapshots snaps)
           (t/is (some? flagged) "a commit must set :is-variant-container")
           (doseq [snap before-flag]
             (let [ffile (:file snap)
                   fmain (get (page-objects ffile) main-id)
                   fcomp (cthc/get-component ffile :c01)]
               (t/is (= "Group / Button" (:name fmain)))
               (t/is (= "Group / Button" (component-full-name fcomp)))))
           (when flagged
             (let [ffile (:file flagged)
                   fobjs (page-objects ffile)
                   fcont (get fobjs variant-id)
                   fmain (get fobjs main-id)
                   fcomp (cthc/get-component ffile :c01)]
               (t/is (= "Group" (:name fcont) (:name fmain) (component-full-name fcomp)))
               (t/is (= variant-id (:variant-id fmain) (:variant-id fcomp)))
               (t/is (= (ctv/properties-to-name (:variant-properties fcomp))
                        (:variant-name fmain)))))
           (t/is (ctk/is-variant-container? container'))
           (t/is (= "Group" (:name container') (:name main') (component-full-name comp')))
           (t/is (= variant-id (:variant-id main') (:variant-id comp')))))
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
         (let [snaps       @snapshots
               file'       (ths/get-file-from-state state)
               objects     (page-objects file')
               container   (get objects variant-id)
               m01'        (get objects id1)
               m02'        (get objects id2)
               c01'        (cthc/get-component file' :c01)
               c02'        (cthc/get-component file' :c02)
               names1      (mapv :name (:variant-properties c01'))
               names2      (mapv :name (:variant-properties c02'))
               ordered     (mapv :main-instance-id
                                 (cfv/find-variant-components (:data file') objects variant-id))
               before-flag (take-while
                            #(not (ctk/is-variant-container?
                                   (get (page-objects (:file %)) variant-id)))
                            snaps)
               flagged     (first-flagged snaps variant-id)]
           (assert-valid-snapshots snaps)
           (doseq [snap before-flag]
             (let [ffile (:file snap)
                   fmain (get (page-objects ffile) id1)
                   fcomp (cthc/get-component ffile :c01)]
               (t/is (= "Alpha" (:name fmain)))
               (t/is (= "Alpha" (component-full-name fcomp)))))
           (when flagged
             (let [ffile (:file flagged)
                   fobjs (page-objects ffile)
                   fcont (get fobjs variant-id)
                   fmain (get fobjs id1)
                   fcomp (cthc/get-component ffile :c01)]
               (t/is (= "Component" (:name fcont) (:name fmain) (component-full-name fcomp)))))
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

(t/deftest combine-as-variants-shared-prefix-validates-after-every-commit
  (t/async
    done
    (let [file       (two-mains-shared-prefix-file)
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
         (let [snaps       @snapshots
               file'       (ths/get-file-from-state state)
               objects     (page-objects file')
               container   (get objects variant-id)
               m01'        (get objects id1)
               m02'        (get objects id2)
               c01'        (cthc/get-component file' :c01)
               c02'        (cthc/get-component file' :c02)
               flagged     (first-flagged snaps variant-id)]
           (assert-valid-snapshots snaps)
           (doseq [{:keys [file]} snaps]
             (let [objs (page-objects file)
                   cont (get objs variant-id)
                   kids (set (:shapes cont))]
               (when (contains? kids id2)
                 (t/is (ctk/is-variant-container? cont)
                       "other mains must not enter the frame before it is a variant container"))))
           (when flagged
             (let [ffile (:file flagged)
                   fobjs (page-objects ffile)
                   fcont (get fobjs variant-id)
                   fmain (get fobjs id1)
                   fcomp (cthc/get-component ffile :c01)]
               (t/is (= "UI" (:name fcont) (:name fmain) (component-full-name fcomp)))))
           (t/is (ctk/is-variant-container? container))
           (t/is (= "UI" (:name container) (:name m01') (:name m02')
                    (component-full-name c01') (component-full-name c02')))
           (t/is (= variant-id (:variant-id m01') (:variant-id m02')
                    (:variant-id c01') (:variant-id c02')))
           (t/is (= variant-id (:parent-id m01') (:parent-id m02')))))
       after-commits-idle))))

(t/deftest combine-as-variants-same-name-gives-unique-values
  (t/async
    done
    (let [file       (three-same-name-mains-file)
          ids        [(cthi/id :m01) (cthi/id :m02) (cthi/id :m03)]
          variant-id (uuid/next)
          store      (ths/setup-store file)]
      (ths/run-store
       store
       done
       [(dwv/combine-as-variants ids {:variant-id variant-id :trigger "test"})]
       (fn [state]
         (let [file'  (ths/get-file-from-state state)
               values (mapv #(-> (cthc/get-component file' %) :variant-properties first :value)
                            [:c01 :c02 :c03])]
           (cthf/validate-file! file')
           (t/is (= ["Value 1" "Value 2" "Value 3"] values))))
       after-commits-idle))))

(t/deftest combine-as-variants-same-name-values-follow-combine-order
  ;; The combine order differs from the layer order of the mains
  (t/async
    done
    (let [file       (three-same-name-mains-file)
          ids        [(cthi/id :m03) (cthi/id :m01) (cthi/id :m02)]
          variant-id (uuid/next)
          store      (ths/setup-store file)]
      (ths/run-store
       store
       done
       [(dwv/combine-as-variants ids {:variant-id variant-id :trigger "test"})]
       (fn [state]
         (let [file'   (ths/get-file-from-state state)
               objects (page-objects file')
               values  (mapv #(-> (cthc/get-component file' %) :variant-properties first :value)
                             [:c03 :c01 :c02])
               ordered (->> (cfv/find-variant-components (:data file') objects variant-id)
                            (mapv #(-> % :variant-properties first :value)))]
           (cthf/validate-file! file')
           (t/is (= ["Value 1" "Value 2" "Value 3"] values))
           (t/is (= ["Value 1" "Value 2" "Value 3"] ordered))))
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

(defn- setup-file
  "A variant set (members :m01 and :m02, each with a child), a copy of :m01,
   two plain components and a copy of the first."
  []
  (-> (cthf/sample-file :file1)
      (cthv/add-variant-with-child :v01 :c01 :m01 :c02 :m02 :m01-child :m02-child)
      (cthc/instantiate-component :c01 :copy01 :children-labels [:copy01-child])
      (ctho/add-simple-component :plain1 :plain1-root :plain1-child)
      (ctho/add-simple-component :plain2 :plain2-root :plain2-child)
      (cthc/instantiate-component :plain1 :plain1-copy)))

(defn- state-of
  [file]
  @(ths/setup-store file))

(t/deftest valid-components-for-variants
  (let [file    (setup-file)
        state   (state-of file)
        page-id (:current-page-id state)
        valid?  #(dwv/valid-components-for-variants? state page-id (mapv cthi/id %))]
    (t/testing "two plain main roots"
      (t/is (valid? [:plain1-root :plain2-root])))
    (t/testing "fewer than two distinct components"
      (t/is (not (valid? [:plain1-root])))
      (t/is (not (valid? [:plain1-root :plain1-root]))))
    (t/testing "a variant main"
      (t/is (not (valid? [:plain1-root :m01]))))
    (t/testing "a copy"
      (t/is (not (valid? [:plain1-root :plain1-copy]))))
    (t/testing "a page that is not the current one"
      (t/is (not (dwv/valid-components-for-variants?
                  state (uuid/next) [(cthi/id :plain1-root) (cthi/id :plain2-root)]))))))

(t/deftest valid-variant-switch
  (let [file   (setup-file)
        state  (state-of file)
        valid? (fn [label pos val]
                 (dwv/valid-variant-switch? state (cths/get-shape file label) pos val))]
    (t/testing "a variant copy head and a value some member holds"
      (t/is (valid? :copy01 0 "Value2"))
      (t/is (valid? :copy01 0 "Value1")))
    (t/testing "a value no member holds"
      (t/is (not (valid? :copy01 0 "Value3"))))
    (t/testing "a position outside the properties"
      (t/is (not (valid? :copy01 1 "Value2")))
      (t/is (not (valid? :copy01 -1 "Value2"))))
    (t/testing "a value that is not a string"
      (t/is (not (valid? :copy01 0 2))))
    (t/testing "not a variant copy head"
      (t/is (not (valid? :m01 0 "Value2")))
      (t/is (not (valid? :copy01-child 0 "Value2")))
      (t/is (not (valid? :plain1-copy 0 "Value2"))))))

(defn- all-transactions-committed
  "A `run-store` stopper: the first undo commit after which no transaction
   is pending, for events that commit their transaction asynchronously."
  [store]
  (fn [stream]
    (->> stream
         (rx/filter (ptk/type? ::dwu/commit-undo-transaction))
         (rx/filter #(empty? (get-in @store [:workspace-undo :transactions-pending]))))))

(defn- go-to-page
  "Stands in for `dcm/go-to-workspace`, which needs the router: makes
   `page-id` the current page and announces it as initialized."
  [& {:keys [page-id]}]
  (ptk/reify ::go-to-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-page-id page-id))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event :app.main.data.workspace.pages/initialize-page {})))))

(t/deftest combine-on-another-page
  (t/async
    done
    (mock/with-mocks
      {dcm/go-to-workspace go-to-page}
      (fn [inner-done]
        (let [file    (cthf/sample-file :file1)
              page-1  (cthf/current-page-id file)
              file    (-> file
                          (cthf/add-sample-page :page2)
                          (ctho/add-simple-component :plain1 :plain1-root :plain1-child)
                          (ctho/add-simple-component :plain2 :plain2-root :plain2-child)
                          (vary-meta assoc :current-page-id page-1))
              store   (ths/setup-store file)
              ids     [(cthi/id :plain1-root) (cthi/id :plain2-root)]]
          (ths/run-store
           store inner-done
           [(dwv/combine-as-variants ids {:page-id (cthi/id :page2)
                                          :trigger "plugin:combine-as-variants"})]
           (fn [state]
             (let [data (:data (ths/get-file-from-state state))
                   c1   (ctkl/get-component data (cthi/id :plain1))
                   c2   (ctkl/get-component data (cthi/id :plain2))]
               (t/is (= (cthi/id :page2) (:current-page-id state)))
               (t/is (some? (:variant-id c1)))
               (t/is (= (:variant-id c1) (:variant-id c2)))))
           (all-transactions-committed store))))
      done)))

(t/deftest switch-into-a-nesting-loop
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (cthv/add-variant :v01 :c01 :m01 :c02 :m02)
                     (cthc/instantiate-component :c01 :nested :parent-label :m02))
          store  (ths/setup-store file)
          errors (atom 0)
          params (with-meta {:shapes [(cths/get-shape file :nested)] :pos 0 :val "Value2"}
                   {:on-error #(swap! errors inc)})]
      (ths/run-store
       store done
       [(dwv/variants-switch params)]
       (fn [state]
         (t/is (= 1 @errors))
         (t/is (= (cthi/id :c01)
                  (:component-id (cths/get-shape (ths/get-file-from-state state) :nested)))))))))

(defn- object-count
  [file]
  (count (:objects (cthf/current-page file))))

(defn- run-selected
  "Run `event` on a store for `file` with the shapes `labels` selected, then
   `check` the resulting file."
  [done file labels event check]
  (ths/run-store
   (ths/setup-store file) done
   [(dws/select-shapes (into (d/ordered-set) (map cthi/id) labels)) event]
   #(check (ths/get-file-from-state %))))

(defn- file-with-a-rect
  []
  (-> (cthf/sample-file :file1)
      (cths/add-sample-shape :rect)))

(t/deftest duplicate-shortcut-with-nothing-selected
  (t/async
    done
    (let [file (file-with-a-rect)]
      (run-selected done file [] (dwv/duplicate-or-add-variant)
                    #(t/is (= (object-count file) (object-count %)))))))

(t/deftest duplicate-shortcut-on-a-plain-shape
  (t/async
    done
    (let [file (file-with-a-rect)]
      (run-selected done file [:rect] (dwv/duplicate-or-add-variant)
                    #(t/is (= (inc (object-count file)) (object-count %)))))))

(t/deftest create-component-shortcut-on-a-plain-shape
  (t/async
    done
    (run-selected done (file-with-a-rect) [:rect] (dwv/add-component-or-variant)
                  #(t/is (= 1 (count (ctkl/components-seq (:data %))))))))

(t/deftest rename-of-a-plain-component
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (ctho/add-simple-component :plain1 :plain1-root :plain1-child))]
      (ths/run-store
       (ths/setup-store file) done
       [(dwv/rename-comp-or-variant-and-main (cthi/id :plain1) "Renamed")]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= "Renamed" (:name (ctkl/get-component (:data file') (cthi/id :plain1)))))
           (t/is (= "Renamed" (:name (cths/get-shape file' :plain1-root))))))))))

(t/deftest remove-empty-properties-of-a-set-with-many-properties
  (t/async
    done
    (let [props (fn [value]
                  (mapv (fn [i]
                          {:name  (str "Property " (inc i))
                           :value (if (= i 5) "" value)})
                        (range 10)))
          file  (-> (cthf/sample-file :file1)
                    (cthv/add-variant :v01 :c01 :m01 :c02 :m02)
                    (cthc/update-component :c01 {:variant-properties (props "a")})
                    (cthc/update-component :c02 {:variant-properties (props "b")}))]
      (ths/run-store
       (ths/setup-store file) done
       [(dwv/remove-empty-properties (cthi/id :v01))]
       (fn [state]
         (let [data (:data (ths/get-file-from-state state))]
           (t/is (= (remove #{"Property 6"} (map :name (props "a")))
                    (map :name (:variant-properties (ctkl/get-component data (cthi/id :c01))))))))))))
