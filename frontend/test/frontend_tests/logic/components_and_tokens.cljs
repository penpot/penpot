;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
(ns frontend-tests.logic.components-and-tokens
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as geom]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-base-file
  []
  (-> (cthf/sample-file :file1)
      (ctht/add-tokens-lib)
      (ctht/update-tokens-lib #(-> %
                                   (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                      :name "test-token-set"))
                                   (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                          :sets #{"test-token-set"}))
                                   (ctob/set-active-themes #{"/test-theme"})
                                   (ctob/add-token (cthi/id :test-token-set)
                                                   (ctob/make-token :id (cthi/new-id! :test-token-1)
                                                                    :name "test-token-1"
                                                                    :type :border-radius
                                                                    :value 25))
                                   (ctob/add-token (cthi/id :test-token-set)
                                                   (ctob/make-token :id (cthi/new-id! :test-token-2)
                                                                    :name "test-token-2"
                                                                    :type :border-radius
                                                                    :value 50))
                                   (ctob/add-token (cthi/id :test-token-set)
                                                   (ctob/make-token :id (cthi/new-id! :test-token-3)
                                                                    :name "test-token-3"
                                                                    :type :border-radius
                                                                    :value 75))))
      (ctho/add-frame :frame1)
      (ctht/apply-token-to-shape :frame1 "test-token-1" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 25)))

(defn- setup-file-with-main
  []
  (-> (setup-base-file)
      (cthc/make-component :component1 :frame1)))

(defn- setup-file-with-copy
  []
  (-> (setup-file-with-main)
      (cthc/instantiate-component :component1 :c-frame1)))

(def flex-layout-attrs
  {:layout                 :flex
   :layout-flex-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :stretch
   :layout-wrap-type       :nowrap
   :layout-padding-type    :simple})

(defn- setup-spacing-file-with-copy
  []
  (let [file       (-> (cthf/sample-file :file1)
                       (ctht/add-tokens-lib)
                       (ctht/update-tokens-lib #(-> %
                                                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                                       :name "test-token-set"))
                                                    (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                                           :sets #{"test-token-set"}))
                                                    (ctob/set-active-themes #{"/test-theme"})
                                                    (ctob/add-token (cthi/id :test-token-set)
                                                                    (ctob/make-token :id (cthi/new-id! :space-s)
                                                                                     :name "space.s"
                                                                                     :type :spacing
                                                                                     :value 12))
                                                    (ctob/add-token (cthi/id :test-token-set)
                                                                    (ctob/make-token :id (cthi/new-id! :space-m)
                                                                                     :name "space.m"
                                                                                     :type :spacing
                                                                                     :value 36))))
                       (ctho/add-frame-with-text :frame1
                                                 :text1
                                                 "Original text"
                                                 :frame-params (merge flex-layout-attrs
                                                                      {:layout-padding {:p1 12 :p2 12 :p3 12 :p4 12}})
                                                 :child-params {:x 12 :y 12})
                       (ctht/apply-token-to-shape :frame1 "space.s" [:p1 :p2 :p3 :p4] [:layout-padding] {:p1 12 :p2 12 :p3 12 :p4 12})
                       (cthc/make-component :component1 :frame1)
                       (cthc/instantiate-component :component1 :c-frame1 {:children-labels [:c-text1]}))
        page       (cthf/current-page file)
        copy-child (cths/get-shape file :c-text1)
        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id copy-child)}
                                               #(assoc-in % [:content :children 0 :children 0 :text] "Overridden text")
                                               (:objects page)
                                               {})]
    (cthf/apply-changes file changes)))

(t/deftest create-component-with-token
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-base-file)
          store    (ths/setup-store file)

          ;; ==== Action
          events
          [(dws/select-shape (cthi/id :frame1))
           (dwl/add-component)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               frame1'        (cths/get-shape file' :frame1)
               tokens-frame1' (:applied-tokens frame1')]

           ;; ==== Check
           (t/is (= (count tokens-frame1') 4))
           (t/is (= (get tokens-frame1' :r1) "test-token-1"))
           (t/is (= (get tokens-frame1' :r2) "test-token-1"))
           (t/is (= (get tokens-frame1' :r3) "test-token-1"))
           (t/is (= (get tokens-frame1' :r4) "test-token-1"))
           (t/is (= (get frame1' :r1) 25))
           (t/is (= (get frame1' :r2) 25))
           (t/is (= (get frame1' :r3) 25))
           (t/is (= (get frame1' :r4) 25))))))))

(t/deftest create-copy-with-token
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-main)
          store    (ths/setup-store file)

          ;; ==== Action
          events
          [(dwl/instantiate-component (:id file)
                                      (cthi/id :component1)
                                      (geom/point 0 0))]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               selected       (dsh/lookup-selected new-state)
               c-frame1'      (dsh/lookup-shape new-state (first selected))
               tokens-frame1' (:applied-tokens c-frame1')]

           ;; ==== Check
           (t/is (= (count tokens-frame1') 4))
           (t/is (= (get tokens-frame1' :r1) "test-token-1"))
           (t/is (= (get tokens-frame1' :r2) "test-token-1"))
           (t/is (= (get tokens-frame1' :r3) "test-token-1"))
           (t/is (= (get tokens-frame1' :r4) "test-token-1"))
           (t/is (= (get c-frame1' :r1) 25))
           (t/is (= (get c-frame1' :r2) 25))
           (t/is (= (get c-frame1' :r3) 25))
           (t/is (= (get c-frame1' :r4) 25))))))))

(t/deftest change-token-in-main
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-copy)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(dwta/apply-token {:shape-ids [(cthi/id :frame1)]
                                     :attributes #{:r1 :r2 :r3 :r4}
                                     :token (toht/get-token file "test-token-2")
                                     :on-update-shape dwta/update-shape-radius})]

          step2 (fn [_]
                  (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                    (ths/run-store
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'          (ths/get-file-from-state new-state)
                             c-frame1'      (cths/get-shape file' :c-frame1)
                             tokens-frame1' (:applied-tokens c-frame1')]

                         ;; ==== Check
                         (t/is (= (count tokens-frame1') 4))
                         (t/is (= (get tokens-frame1' :r1) "test-token-2"))
                         (t/is (= (get tokens-frame1' :r2) "test-token-2"))
                         (t/is (= (get tokens-frame1' :r3) "test-token-2"))
                         (t/is (= (get tokens-frame1' :r4) "test-token-2"))
                         (t/is (= (get c-frame1' :r1) 50))
                         (t/is (= (get c-frame1' :r2) 50))
                         (t/is (= (get c-frame1' :r3) 50))
                         (t/is (= (get c-frame1' :r4) 50)))))))]

      (tohs/run-store-async
       store step2 events identity))))

(t/deftest change-spacing-token-in-main-updates-copy-layout
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-spacing-file-with-copy)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(dwta/apply-token {:shape-ids [(cthi/id :frame1)]
                                     :attributes #{:p1 :p2 :p3 :p4}
                                     :token (toht/get-token file "space.m")
                                     :on-update-shape dwta/apply-spacing-token})]

          step2 (fn [_]
                  ;; Reset the WASM call tracking so the assertions below only
                  ;; account for the reflow triggered by the library sync, not
                  ;; the earlier token application on the main component.
                  (thw/reset-call-counts!)
                  (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                    (ths/run-store
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'     (ths/get-file-from-state new-state)
                             c-frame1' (cths/get-shape file' :c-frame1)]

                         ;; ==== Check
                         (t/is (= (:applied-tokens c-frame1') {:p1 "space.m"
                                                               :p2 "space.m"
                                                               :p3 "space.m"
                                                               :p4 "space.m"}))
                         (t/is (= (:layout-padding c-frame1') {:p1 36 :p2 36 :p3 36 :p4 36}))

                         ;; The library sync reflows the copied layout through
                         ;; the WASM pipeline. With the render-wasm renderer the
                         ;; final child geometry is computed by the Rust engine
                         ;; (mocked here), so we assert the reflow path was
                         ;; exercised rather than the resulting coordinates.
                         (t/is (pos? (thw/call-count :set-structure-modifiers)))
                         (t/is (pos? (thw/call-count :propagate-modifiers))))))))]

      (tohs/run-store-async
       store step2 events identity))))

(t/deftest remove-token-in-main
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-copy)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(dwta/unapply-token {:shape-ids [(cthi/id :frame1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token-name "test-token-1"})]

          step2 (fn [_]
                  (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                    (ths/run-store
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'          (ths/get-file-from-state new-state)
                             c-frame1'      (cths/get-shape file' :c-frame1)
                             tokens-frame1' (:applied-tokens c-frame1')]

                         ;; ==== Check
                         (t/is (= (count tokens-frame1') 0))
                         (t/is (= (get c-frame1' :r1) 25))
                         (t/is (= (get c-frame1' :r2) 25))
                         (t/is (= (get c-frame1' :r3) 25))
                         (t/is (= (get c-frame1' :r4) 25)))))))]

      (tohs/run-store-async
       store step2 events identity))))

(t/deftest modify-token
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-copy)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(dwtl/set-selected-token-set-id (cthi/id :test-token-set))
                  (dwtl/update-token (cthi/id :test-token-1)
                                     {:name "test-token-1"
                                      :type :border-radius
                                      :value 66})]

          step2 (fn [_]
                  (let [events2 [(dwtp/propagate-workspace-tokens)
                                 (dwl/sync-file (:id file) (:id file))]]
                    (tohs/run-store-async
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'          (ths/get-file-from-state new-state)
                             c-frame1'      (cths/get-shape file' :c-frame1)
                             tokens-frame1' (:applied-tokens c-frame1')]

                         ;; ==== Check
                         (t/is (= (count tokens-frame1') 4))
                         (t/is (= (get tokens-frame1' :r1) "test-token-1"))
                         (t/is (= (get tokens-frame1' :r2) "test-token-1"))
                         (t/is (= (get tokens-frame1' :r3) "test-token-1"))
                         (t/is (= (get tokens-frame1' :r4) "test-token-1"))
                         (t/is (= (get c-frame1' :r1) 66))
                         (t/is (= (get c-frame1' :r2) 66))
                         (t/is (= (get c-frame1' :r3) 66))
                         (t/is (= (get c-frame1' :r4) 66)))))))]

      (tohs/run-store-async
       store step2 events identity))))

(t/deftest change-token-in-copy-then-change-main
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-copy)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(dwta/apply-token {:shape-ids [(cthi/id :c-frame1)]
                                     :attributes #{:r1 :r2 :r3 :r4}
                                     :token (toht/get-token file "test-token-2")
                                     :on-update-shape dwta/update-shape-radius})
                  (dwta/apply-token {:shape-ids [(cthi/id :frame1)]
                                     :attributes #{:r1 :r2 :r3 :r4}
                                     :token (toht/get-token file "test-token-3")
                                     :on-update-shape dwta/update-shape-radius})]

          step2 (fn [_]
                  (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                    (ths/run-store
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'          (ths/get-file-from-state new-state)
                             c-frame1'      (cths/get-shape file' :c-frame1)
                             tokens-frame1' (:applied-tokens c-frame1')]

                         ;; ==== Check
                         (t/is (= (count tokens-frame1') 4))
                         (t/is (= (get tokens-frame1' :r1) "test-token-2"))
                         (t/is (= (get tokens-frame1' :r2) "test-token-2"))
                         (t/is (= (get tokens-frame1' :r3) "test-token-2"))
                         (t/is (= (get tokens-frame1' :r4) "test-token-2"))
                         (t/is (= (get c-frame1' :r1) 50))
                         (t/is (= (get c-frame1' :r2) 50))
                         (t/is (= (get c-frame1' :r3) 50))
                         (t/is (= (get c-frame1' :r4) 50)))))))]

      (tohs/run-store-async
       store step2 events identity))))

(t/deftest remove-token-in-copy-then-change-main
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-copy)
          store    (ths/setup-store file)

          ;; ==== Action
          events [(dwta/unapply-token {:shape-ids [(cthi/id :c-frame1)]
                                       :attributes #{:r1 :r2 :r3 :r4}
                                       :token-name  "test-token-1"})
                  (dwta/apply-token {:shape-ids [(cthi/id :frame1)]
                                     :attributes #{:r1 :r2 :r3 :r4}
                                     :token (toht/get-token file "test-token-3")
                                     :on-update-shape dwta/update-shape-radius})]

          step2 (fn [_]
                  (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                    (ths/run-store
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'          (ths/get-file-from-state new-state)
                             c-frame1'      (cths/get-shape file' :c-frame1)
                             tokens-frame1' (:applied-tokens c-frame1')]

                         ;; ==== Check
                         (t/is (= (count tokens-frame1') 0))
                         (t/is (= (get c-frame1' :r1) 25))
                         (t/is (= (get c-frame1' :r2) 25))
                         (t/is (= (get c-frame1' :r3) 25))
                         (t/is (= (get c-frame1' :r4) 25)))))))]

      (tohs/run-store-async
       store step2 events identity))))

(t/deftest modify-token-all-types
  (t/async
    done
    (let [;; ==== Setup
          file  (-> (cthf/sample-file :file1)
                    (ctht/add-tokens-lib)
                    (ctht/update-tokens-lib #(-> %
                                                 (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                                    :name "test-token-set"))
                                                 (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                                        :sets #{"test-token-set"}))
                                                 (ctob/set-active-themes #{"/test-theme"})
                                                 (ctob/add-token (cthi/id :test-token-set)
                                                                 (ctob/make-token :id (cthi/new-id! :token-radius)
                                                                                  :name "token-radius"
                                                                                  :type :border-radius
                                                                                  :value 10))
                                                 (ctob/add-token (cthi/id :test-token-set)
                                                                 (ctob/make-token :id (cthi/new-id! :token-rotation)
                                                                                  :name "token-rotation"
                                                                                  :type :rotation
                                                                                  :value 30))
                                                 (ctob/add-token (cthi/id :test-token-set)
                                                                 (ctob/make-token :id (cthi/new-id! :token-opacity)
                                                                                  :name "token-opacity"
                                                                                  :type :opacity
                                                                                  :value 0.7))
                                                 (ctob/add-token (cthi/id :test-token-set)
                                                                 (ctob/make-token :id (cthi/new-id! :token-stroke-width)
                                                                                  :name "token-stroke-width"
                                                                                  :type :stroke-width
                                                                                  :value 2))
                                                 (ctob/add-token (cthi/id :test-token-set)
                                                                 (ctob/make-token :id (cthi/new-id! :token-color)
                                                                                  :name "token-color"
                                                                                  :type :color
                                                                                  :value "#00ff00"))
                                                 (ctob/add-token (cthi/id :test-token-set)
                                                                 (ctob/make-token :id (cthi/new-id! :token-dimensions)
                                                                                  :name "token-dimensions"
                                                                                  :type :dimensions
                                                                                  :value 100))))
                    (ctho/add-frame :frame1)
                    (ctht/apply-token-to-shape :frame1 "token-radius" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
                    (ctht/apply-token-to-shape :frame1 "token-rotation" [:rotation] [:rotation] 30)
                    (ctht/apply-token-to-shape :frame1 "token-opacity" [:opacity] [:opacity] 0.7)
                    (ctht/apply-token-to-shape :frame1 "token-stroke-width" [:stroke-width] [:stroke-width] 2)
                    (ctht/apply-token-to-shape :frame1 "token-color" [:stroke-color] [:stroke-color] "#00ff00")
                    (ctht/apply-token-to-shape :frame1 "token-color" [:fill] [:fill] "#00ff00")
                    (ctht/apply-token-to-shape :frame1 "token-dimensions" [:width :height] [:width :height] 100)
                    (cthc/make-component :component1 :frame1)
                    (cthc/instantiate-component :component1 :c-frame1))
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwtl/set-selected-token-set-id (cthi/id :test-token-set))
                  (dwtl/update-token (cthi/id :token-radius)
                                     {:name "token-radius"
                                      :value 30})
                  (dwtl/update-token (cthi/id :token-rotation)
                                     {:name "token-rotation"
                                      :value 45})
                  (dwtl/update-token (cthi/id :token-opacity)
                                     {:name "token-opacity"
                                      :value 0.9})
                  (dwtl/update-token (cthi/id :token-stroke-width)
                                     {:name "token-stroke-width"
                                      :value 8})
                  (dwtl/update-token (cthi/id :token-color)
                                     {:name "token-color"
                                      :value "#ff0000"})
                  (dwtl/update-token (cthi/id :token-dimensions)
                                     {:name "token-dimensions"
                                      :value 200})]

          step2 (fn [_]
                  (let [events2 [(dwtp/propagate-workspace-tokens)
                                 (dwl/sync-file (:id file) (:id file))]]
                    (tohs/run-store-async
                     store done events2
                     (fn [new-state]
                       (let [;; ==== Get
                             file'          (ths/get-file-from-state new-state)
                             c-frame1'      (cths/get-shape file' :c-frame1)
                             tokens-frame1' (:applied-tokens c-frame1')]

                         ;; ==== Check
                         (t/is (= (count tokens-frame1') 11))
                         (t/is (= (get tokens-frame1' :r1) "token-radius"))
                         (t/is (= (get tokens-frame1' :r2) "token-radius"))
                         (t/is (= (get tokens-frame1' :r3) "token-radius"))
                         (t/is (= (get tokens-frame1' :r4) "token-radius"))
                         (t/is (= (get tokens-frame1' :rotation) "token-rotation"))
                         (t/is (= (get tokens-frame1' :opacity) "token-opacity"))
                         (t/is (= (get tokens-frame1' :stroke-width) "token-stroke-width"))
                         (t/is (= (get tokens-frame1' :stroke-color) "token-color"))
                         (t/is (= (get tokens-frame1' :fill) "token-color"))
                         (t/is (= (get tokens-frame1' :width) "token-dimensions"))
                         (t/is (= (get tokens-frame1' :height) "token-dimensions"))
                         (t/is (= (get c-frame1' :r1) 30))
                         (t/is (= (get c-frame1' :r2) 30))
                         (t/is (= (get c-frame1' :r3) 30))
                         (t/is (= (get c-frame1' :r4) 30))
                         (t/is (= (get c-frame1' :rotation) 45))
                         (t/is (= (get c-frame1' :opacity) 0.9))
                         (t/is (= (get-in c-frame1' [:strokes 0 :stroke-width]) 8))
                         (t/is (= (get-in c-frame1' [:strokes 0 :stroke-color]) "#ff0000"))
                         (t/is (= (-> c-frame1' :fills (nth 0) :fill-color) "#ff0000"))
                         (t/is (mth/close? (get c-frame1' :width) 200))
                         (t/is (mth/close? (get c-frame1' :height) 200))

                         (t/is (empty? (:touched c-frame1'))))))))]

      (tohs/run-store-async
       store step2 events identity))))
