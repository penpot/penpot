;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-variant-rename-test
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.main.data.workspace :as dw]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(defn- variant-file
  []
  (-> (cthf/sample-file :file1)
      (thv/add-variant :variant :comp1 :root1 :comp2 :root2
                       :variant1-params {:name "Button"}
                       :variant2-params {:name "Button"})
      (cths/update-shape :variant :name "Button")))

(defn- without-variant-name
  [file label]
  (let [page-id  (cthf/current-page-id file)
        shape-id (cthi/id label)]
    (ctf/update-file-data
     file
     (fn [data]
       (ctpl/update-page data
                         page-id
                         #(update-in % [:objects shape-id] dissoc :variant-name))))))

(defn- prop-value
  [component]
  (-> component :variant-properties first :value))

(defn- run
  "Run workspace events, then validate the file before assertions.

   Pass `:validate? false` only when the fixture is intentionally incomplete
   (e.g. a variant main without `:variant-name`)."
  [file events done assert-fn & {:keys [validate?] :or {validate? true}}]
  (ths/run-store
   (ths/setup-store file)
   done
   events
   (fn [state]
     (when validate?
       (cthf/validate-file! (ths/get-file-from-state state)))
     (assert-fn state))))

(t/deftest end-rename-shape-does-not-apply-variant-formula
  (t/async
    done
    (let [file (variant-file)
          component (cthc/get-component file :comp1)
          original-name (:name component)
          original-path (:path component)
          root2-id (cthi/id :root2)]
      (run file
           [(dw/start-rename-shape (cthi/id :root1))
            (dw/end-rename-shape (cthi/id :root1) "Property 1=another 21")
            (dw/start-rename-shape root2-id)]
           done
           (fn [state]
             (let [file (ths/get-file-from-state state)
                   shape (cths/get-shape file :root1)
                   component (cthc/get-component file :comp1)]
               ;; Formula updates belong to rename-shape-or-variant.
               (t/is (= "Value1" (prop-value component)))
               (t/is (= "Value1" (:variant-name shape)))
               (t/is (= "Button" (:name shape)))
               (t/is (= "Button" (:name (cths/get-shape file :variant))))
               (t/is (= original-name (:name component)))
               (t/is (= original-path (:path component)))
               (t/is (= "Value2" (prop-value (cthc/get-component file :comp2))))
               (t/is (= root2-id (:shape-for-rename (:workspace-local state))))))))))

(t/deftest rename-shape-or-variant-updates-variant-value-and-keeps-layer-name
  (t/async
    done
    (let [file (variant-file)
          component (cthc/get-component file :comp1)
          original-name (:name component)
          original-path (:path component)]
      (run file
           [(dw/start-rename-shape (cthi/id :root1))
            (dw/rename-shape-or-variant (cthi/id :root1) "Property 1=another 21")]
           done
           (fn [state]
             (let [file (ths/get-file-from-state state)
                   shape (cths/get-shape file :root1)
                   component (cthc/get-component file :comp1)]
               (t/is (= "another 21" (prop-value component)))
               (t/is (= "another 21" (:variant-name shape)))
               (t/is (= "Button" (:name shape)))
               (t/is (= original-name (:name component)))
               (t/is (= original-path (:path component)))
               (t/is (nil? (:shape-for-rename (:workspace-local state))))))))))

(t/deftest rename-shape-or-variant-clears-shape-for-rename-on-malformed-formula
  (t/async
    done
    (run (variant-file)
         [(dw/start-rename-shape (cthi/id :root1))
          (dw/rename-shape-or-variant (cthi/id :root1) "not-a-formula")]
         done
         (fn [state]
           (let [file (ths/get-file-from-state state)
                 shape (cths/get-shape file :root1)]
             (t/is (= "Button" (:name shape)))
             (t/is (= "not-a-formula" (:variant-error shape)))
             (t/is (= "" (prop-value (cthc/get-component file :comp1))))
             (t/is (nil? (:shape-for-rename (:workspace-local state)))))))))

(t/deftest rename-shape-or-variant-blank-name-does-not-clear-variant-values
  (t/async
    done
    (run (variant-file)
         [(dw/start-rename-shape (cthi/id :root1))
          (dw/rename-shape-or-variant (cthi/id :root1) "")]
         done
         (fn [state]
           (let [file (ths/get-file-from-state state)
                 shape (cths/get-shape file :root1)]
             (t/is (= "Button" (:name shape)))
             (t/is (= "Value1" (:variant-name shape)))
             (t/is (= "Value1" (prop-value (cthc/get-component file :comp1))))
             (t/is (nil? (:variant-error shape)))
             (t/is (nil? (:shape-for-rename (:workspace-local state)))))))))

(t/deftest end-rename-shape-nil-leaves-variant-main-unchanged
  (t/async
    done
    (run (variant-file)
         [(dw/start-rename-shape (cthi/id :root1))
          (dw/end-rename-shape (cthi/id :root1) nil)]
         done
         (fn [state]
           (let [file (ths/get-file-from-state state)
                 shape (cths/get-shape file :root1)]
             (t/is (= "Button" (:name shape)))
             (t/is (= "Value1" (:variant-name shape)))
             (t/is (= "Value1" (prop-value (cthc/get-component file :comp1))))
             (t/is (nil? (:shape-for-rename (:workspace-local state)))))))))

(t/deftest end-rename-shape-ignores-variant-main-without-variant-name
  (t/async
    done
    (let [file (-> (variant-file)
                   (without-variant-name :root1))
          component (cthc/get-component file :comp1)
          original-name (:name component)
          original-path (:path component)
          root2-id (cthi/id :root2)]
      (run file
           [(dw/start-rename-shape (cthi/id :root1))
            (dw/end-rename-shape (cthi/id :root1) "Property 1=another 21")
            (dw/start-rename-shape root2-id)]
           done
           (fn [state]
             (let [file (ths/get-file-from-state state)
                   shape (cths/get-shape file :root1)
                   component (cthc/get-component file :comp1)]
               (t/is (= "Button" (:name shape)))
               (t/is (nil? (:variant-name shape)))
               (t/is (= "Value1" (prop-value component)))
               (t/is (= original-name (:name component)))
               (t/is (= original-path (:path component)))
               (t/is (= root2-id (:shape-for-rename (:workspace-local state))))))
           ;; Fixture starts without :variant-name, so the file is incomplete.
           :validate? false))))

(t/deftest end-rename-shape-keeps-formula-text-off-the-layer-name
  (t/async
    done
    (run (variant-file)
         [(dw/start-rename-shape (cthi/id :root1))
          (dw/end-rename-shape (cthi/id :root1) "not-a-formula")]
         done
         (fn [state]
           (let [file (ths/get-file-from-state state)
                 shape (cths/get-shape file :root1)]
             (t/is (= "Button" (:name shape)))
             (t/is (= "Value1" (:variant-name shape)))
             (t/is (= "Value1" (prop-value (cthc/get-component file :comp1))))
             (t/is (nil? (:variant-error shape)))
             (t/is (nil? (:shape-for-rename (:workspace-local state)))))))))

(t/deftest end-rename-shape-renames-variant-container
  (t/async
    done
    (run (variant-file)
         [(dw/end-rename-shape (cthi/id :variant) "Renamed set")]
         done
         (fn [state]
           (let [file (ths/get-file-from-state state)]
             (t/is (= "Renamed set" (:name (cths/get-shape file :variant))))
             (t/is (= "Renamed set" (:name (cths/get-shape file :root1))))
             (t/is (= "Renamed set" (:name (cths/get-shape file :root2))))
             (t/is (= "Renamed set" (:name (cthc/get-component file :comp1))))
             (t/is (= "Renamed set" (:name (cthc/get-component file :comp2)))))))))

(t/deftest end-rename-shape-renames-plain-shape-and-component-main
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (cths/add-sample-shape :frame :type :frame :name "Frame")
                   (cthc/make-component :comp :frame)
                   (cths/add-sample-shape :rect :name "Rect"))
          rect-id (cthi/id :rect)]
      (run file
           [(dw/start-rename-shape (cthi/id :frame))
            (dw/end-rename-shape (cthi/id :frame) "Renamed")
            (dw/start-rename-shape rect-id)
            (dw/end-rename-shape rect-id "Box")]
           done
           (fn [state]
             (let [file (ths/get-file-from-state state)]
               (t/is (= "Renamed" (:name (cths/get-shape file :frame))))
               (t/is (= "Renamed" (:name (cthc/get-component file :comp))))
               (t/is (= "Box" (:name (cths/get-shape file :rect))))
               (t/is (nil? (:shape-for-rename (:workspace-local state))))))))))
