;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns frontend-tests.logic.components-and-tokens
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.geom.point :as geom]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.objects-map :as omap]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.tokens.remapping :as remap]
   [app.main.data.workspace.transforms :as dwtr]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-base-file
  []
  (-> (ctht/sample-file-with-tokens
       :page-label :page1
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                       :name "test-token-set"))
                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :test-theme)
                                                           :name "test-theme"
                                                           :sets #{"test-token-set"}))
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
                                                     :value 75)))
       :status-fn #(-> %
                       (ctos/set-tokens-status #{(cthi/id :test-theme)}
                                               #{(cthi/id :test-token-set)})))
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
                                     :token (toht/get-token file "test-token-2")})]

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
       store (constantly nil) events step2))))

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
                         (t/is (= (get c-frame1' :r4) 66)))
                       (tohs/stop-on-propagations-done store)))))]

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
                                     :token (toht/get-token file "test-token-2")})
                  (dwta/apply-token {:shape-ids [(cthi/id :frame1)]
                                     :attributes #{:r1 :r2 :r3 :r4}
                                     :token (toht/get-token file "test-token-3")})]

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
                                     :token (toht/get-token file "test-token-3")})]

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
          file  (-> (ctht/sample-file-with-tokens
                     :lib-fn #(-> %
                                  (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                     :name "test-token-set"))
                                  (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :test-theme)
                                                                         :name "test-theme"
                                                                         :sets #{"test-token-set"}))
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
                                                                   :value 100)))
                     :status-fn #(ctos/set-tokens-status % #{(cthi/id :test-theme)} #{(cthi/id :test-token-set)}))
                    (ctho/add-frame :frame1)
                    (ctht/apply-token-to-shape :frame1 "token-radius" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
                    (ctht/apply-token-to-shape :frame1 "token-rotation" [:rotation] [:rotation] 30)
                    (ctht/apply-token-to-shape :frame1 "token-opacity" [:opacity] [:opacity] 0.7)
                    (ctht/apply-token-to-shape :frame1 "token-stroke-width" [:stroke-width-top :stroke-width-right :stroke-width-bottom :stroke-width-left] [:stroke-width] 2)
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
                         (t/is (= (count tokens-frame1') 14))
                         (t/is (= (get tokens-frame1' :r1) "token-radius"))
                         (t/is (= (get tokens-frame1' :r2) "token-radius"))
                         (t/is (= (get tokens-frame1' :r3) "token-radius"))
                         (t/is (= (get tokens-frame1' :r4) "token-radius"))
                         (t/is (= (get tokens-frame1' :rotation) "token-rotation"))
                         (t/is (= (get tokens-frame1' :opacity) "token-opacity"))
                         (t/is (= (get tokens-frame1' :stroke-width-top) "token-stroke-width"))
                         (t/is (= (get tokens-frame1' :stroke-width-right) "token-stroke-width"))
                         (t/is (= (get tokens-frame1' :stroke-width-bottom) "token-stroke-width"))
                         (t/is (= (get tokens-frame1' :stroke-width-left) "token-stroke-width"))
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

                         (t/is (empty? (:touched c-frame1'))))
                       (tohs/stop-on-propagations-done store)))))]

      (tohs/run-store-async
       store step2 events identity))))

(defn- setup-file-with-stale-values
  "Adds :frame2 (with a :rect1 child) and :frame3, whose stored radius
  differs from the resolved token value."
  []
  (-> (setup-base-file)
      (ctho/add-frame-with-child :frame2 :rect1)
      (ctho/add-frame :frame3)
      (ctht/apply-token-to-shape :frame2 "test-token-2" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
      (ctht/apply-token-to-shape :rect1 "test-token-3" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
      (ctht/apply-token-to-shape :frame3 "test-token-2" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)))

(t/deftest scoped-propagation-updates-only-the-scoped-tree
  (t/async
    done
    (let [;; ==== Setup
          file  (setup-file-with-stale-values)
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens
                   {:page-id  (cthf/current-page-id file)
                    :root-ids [(cthi/id :frame2)]})]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame2' (cths/get-shape file' :frame2)
               rect1'  (cths/get-shape file' :rect1)
               frame3' (cths/get-shape file' :frame3)]

           ;; ==== Check
           (t/is (= (:r1 frame2') 50))
           (t/is (= (:r1 rect1') 75))
           (t/is (= (:r1 frame3') 10))))))))

(t/deftest unscoped-propagation-updates-every-shape
  (t/async
    done
    (let [;; ==== Setup
          file  (setup-file-with-stale-values)
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame2' (cths/get-shape file' :frame2)
               rect1'  (cths/get-shape file' :rect1)
               frame3' (cths/get-shape file' :frame3)]

           ;; ==== Check
           (t/is (= (:r1 frame2') 50))
           (t/is (= (:r1 rect1') 75))
           (t/is (= (:r1 frame3') 50))))))))

(t/deftest previous-tokens-limit-propagation-to-changed-tokens
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-stale-values)
          store    (ths/setup-store file)
          previous (-> (cfo/get-tokens-in-active-sets (ctht/get-tokens-status file)
                                                      (ctht/get-tokens-lib file))
                       (assoc-in ["test-token-2" :value] 10))

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens {:previous-tokens [[nil previous]]})]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame2' (cths/get-shape file' :frame2)
               rect1'  (cths/get-shape file' :rect1)
               frame3' (cths/get-shape file' :frame3)]

           ;; ==== Check
           (t/is (= (:r1 frame2') 50))
           (t/is (= (:r1 frame3') 50))
           ;; test-token-3 kept its value, so its stale shape is untouched
           (t/is (= (:r1 rect1') 10))))
       tohs/stop-on-undo-commit))))

(t/deftest propagation-without-changed-tokens-does-nothing
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-with-stale-values)
          store    (ths/setup-store file)
          previous (cfo/get-tokens-in-active-sets (ctht/get-tokens-status file)
                                                  (ctht/get-tokens-lib file))
          emitted  (atom [])
          _        (rx/subs! #(swap! emitted conj (ptk/type %))
                             (ptk/input-stream store))

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens {:previous-tokens [[nil previous]]})]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)
               types  (set @emitted)]

           ;; ==== Check
           (t/is (contains? types ::dwtp/propagation-finished))
           (t/is (not (contains? types ::dwtp/propagate-scope)))
           (t/is (not (contains? types :app.main.data.workspace.undo/start-undo-transaction)))
           ;; Stale values stay, since no token changed
           (t/is (= (:r1 rect1') 10))))))))

(t/deftest dimensions-token-keeps-the-height-with-proportion-lock
  (t/async
    done
    (let [;; ==== Setup
          file  (-> (ctht/sample-file-with-tokens
                     :lib-fn #(-> %
                                  (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                     :name "test-token-set"))
                                  (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :test-theme)
                                                                         :name "test-theme"
                                                                         :sets #{"test-token-set"}))
                                  (ctob/add-token (cthi/id :test-token-set)
                                                  (ctob/make-token :id (cthi/new-id! :token-dimensions)
                                                                   :name "token-dimensions"
                                                                   :type :dimensions
                                                                   :value 200)))
                     :status-fn #(ctos/set-tokens-status % #{(cthi/id :test-theme)} #{(cthi/id :test-token-set)}))
                    (ctho/add-frame :frame-locked :width 100 :height 50)
                    (cths/update-shape :frame-locked :proportion 2)
                    (cths/update-shape :frame-locked :proportion-lock true)
                    (ctht/apply-token-to-shape :frame-locked "token-dimensions" [:width :height] [] nil)
                    (ctho/add-frame :frame-free :width 100 :height 50)
                    (ctht/apply-token-to-shape :frame-free "token-dimensions" [:width :height] [] nil)
                    (ctho/add-frame :frame-sequential :width 100 :height 50)
                    (cths/update-shape :frame-sequential :proportion 2)
                    (cths/update-shape :frame-sequential :proportion-lock true))
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwtr/update-dimensions [(cthi/id :frame-sequential)] :width 200 {:no-wasm? true})
                  (dwtr/update-dimensions [(cthi/id :frame-sequential)] :height 200 {:no-wasm? true})
                  (dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               frame-locked' (cths/get-shape file' :frame-locked)
               frame-free'   (cths/get-shape file' :frame-free)
               frame-seq'    (cths/get-shape file' :frame-sequential)]

           ;; ==== Check
           ;; The height is set last, so with the proportion locked it wins
           (t/is (mth/close? (:height frame-locked') 200))
           (t/is (mth/close? (:width frame-locked') 400))
           ;; The same size as setting the width and then the height
           (t/is (mth/close? (:width frame-seq') (:width frame-locked')))
           (t/is (mth/close? (:height frame-seq') (:height frame-locked')))
           (t/is (mth/close? (:height frame-free') 200))
           (t/is (mth/close? (:width frame-free') 200))))))))

(t/deftest dimension-tokens-resize-in-a-single-commit
  (t/async
    done
    (let [;; ==== Setup
          make-token (fn [lib label value]
                       (ctob/add-token lib (cthi/id :test-token-set)
                                       (ctob/make-token :id (cthi/new-id! label)
                                                        :name (name label)
                                                        :type :dimensions
                                                        :value value)))
          file  (-> (ctht/sample-file-with-tokens
                     :lib-fn #(-> %
                                  (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                     :name "test-token-set"))
                                  (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :test-theme)
                                                                         :name "test-theme"
                                                                         :sets #{"test-token-set"}))
                                  (make-token :token-width 150)
                                  (make-token :token-height 80)
                                  (make-token :token-size 120))
                     :status-fn #(ctos/set-tokens-status % #{(cthi/id :test-theme)} #{(cthi/id :test-token-set)}))
                    (ctho/add-frame :frame-a :width 100 :height 50)
                    (ctht/apply-token-to-shape :frame-a "token-width" [:width] [] nil)
                    (ctht/apply-token-to-shape :frame-a "token-height" [:height] [] nil)
                    (ctho/add-frame :frame-b :width 100 :height 50)
                    (ctht/apply-token-to-shape :frame-b "token-size" [:width :height] [] nil)
                    (ctho/add-frame :frame-c :width 100 :height 50)
                    (ctht/apply-token-to-shape :frame-c "token-height" [:height] [] nil))
          store   (ths/setup-store file)
          commits (atom 0)

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens)]]

      (rx/subs! (fn [_] (swap! commits inc))
                (->> (ptk/input-stream store)
                     (rx/filter dch/commit?)
                     (rx/filter #(seq (:redo-changes (deref %))))))

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame-a (cths/get-shape file' :frame-a)
               frame-b (cths/get-shape file' :frame-b)
               frame-c (cths/get-shape file' :frame-c)]

           ;; ==== Check
           (t/is (= 1 @commits))
           (t/is (mth/close? (:width frame-a) 150))
           (t/is (mth/close? (:height frame-a) 80))
           (t/is (mth/close? (:width frame-b) 120))
           (t/is (mth/close? (:height frame-b) 120))
           (t/is (mth/close? (:width frame-c) 100))
           (t/is (mth/close? (:height frame-c) 80))))))))

(t/deftest padding-and-size-tokens-lay-out-the-padded-board
  (t/async
    done
    (let [;; ==== Setup
          make-token    (fn [lib label type value]
                          (ctob/add-token lib (cthi/id :test-token-set)
                                          (ctob/make-token :id (cthi/new-id! label)
                                                           :name (name label)
                                                           :type type
                                                           :value value)))
          file          (ctht/sample-file-with-tokens
                         :lib-fn #(-> %
                                      (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                         :name "test-token-set"))
                                      (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :test-theme)
                                                                             :name "test-theme"
                                                                             :sets #{"test-token-set"}))
                                      (make-token :token-padding :spacing 20)
                                      (make-token :token-width :dimensions 80))
                         :status-fn #(ctos/set-tokens-status % #{(cthi/id :test-theme)} #{(cthi/id :test-token-set)}))
          first-page-id (cthf/current-page-id file)
          layout        {:layout :flex :layout-flex-dir :row
                         :layout-item-h-sizing :fix :layout-item-v-sizing :fix
                         :layout-padding-type :multiple}
          ;; Page 2, laid out by the resize: :row with :grown and :padded
          file          (-> file
                            (cthf/add-sample-page :page2)
                            (cths/add-sample-shape :row (merge layout {:type :frame :x 0 :y 0 :width 400 :height 100
                                                                       :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}}))
                            (cths/add-sample-shape :grown :parent-label :row :x 0 :y 0 :width 50 :height 20)
                            (cths/add-sample-shape :padded (merge layout {:type :frame :parent-label :row
                                                                          :x 50 :y 0 :width 100 :height 60
                                                                          :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}}))
                            (cths/add-sample-shape :inner :parent-label :padded :x 58 :y 8 :width 10 :height 10)
                            (ctht/apply-token-to-shape :padded "token-padding" [:p1 :p2 :p3 :p4] [] nil)
                            (ctht/apply-token-to-shape :grown "token-width" [:width] [] nil)
                            (vary-meta assoc :current-page-id first-page-id))
          store         (ths/setup-store file)

          ;; ==== Action
          events        [(dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'  (ths/get-file-from-state new-state)
               shape  #(cths/get-shape file' % :page-label :page2)
               padded (shape :padded)
               inner  (shape :inner)]

           ;; ==== Check
           (t/is (mth/close? (:width (shape :grown)) 80))
           (t/is (mth/close? (- (:x inner) (:x padded)) 20))
           (t/is (mth/close? (- (:y inner) (:y padded)) 20))))))))

(defn- watch-updated-ids
  "Atom with the ids of the shapes whose attributes are written by the
  local commits of `store`, leaving out the writes of token values."
  [store]
  (let [ids (atom #{})]
    (rx/subs! (fn [event]
                (swap! ids into
                       (comp (filter #(= :mod-obj (:type %)))
                             (filter #(some (fn [op] (= :set (:type op))) (:operations %)))
                             (map :id))
                       (:redo-changes (deref event))))
              (rx/filter dch/commit? (ptk/input-stream store)))
    ids))

(t/deftest propagation-skips-shapes-that-hold-the-values
  (t/async
    done
    (let [;; ==== Setup
          file    (setup-file-with-stale-values)
          store   (ths/setup-store file)
          updated (watch-updated-ids store)

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame1' (cths/get-shape file' :frame1)
               frame2' (cths/get-shape file' :frame2)]

           ;; ==== Check
           (t/is (= (:r1 frame1') 25))
           (t/is (= (:r1 frame2') 50))
           ;; :frame1 already holds the value of its token
           (t/is (not (contains? @updated (cthi/id :frame1))))
           (t/is (contains? @updated (cthi/id :frame2)))))))))

(defn- encode-page-objects
  "Keeps the objects of the current page of `file` encoded, as they are
  after loading a file."
  [file]
  (let [page-id (cthf/current-page-id file)]
    (update-in file [:data :pages-index page-id :objects]
               #(omap/create (omap/wrap %)))))

(t/deftest propagation-skips-encoded-shapes-without-changed-tokens
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (setup-file-with-stale-values)
                       (ctho/add-frame :frame4)
                       (encode-page-objects))
          store    (ths/setup-store file)
          previous (-> (cfo/get-tokens-in-active-sets (ctht/get-tokens-status file)
                                                      (ctht/get-tokens-lib file))
                       (assoc-in ["test-token-2" :value] 10))

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens {:previous-tokens [[nil previous]]})]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               page-id (cthf/current-page-id file')
               objects (dsh/lookup-page-objects new-state (:id file') page-id)
               frame2' (cths/get-shape file' :frame2)
               frame3' (cths/get-shape file' :frame3)]

           ;; ==== Check
           (t/is (omap/objects-map? objects))
           (t/is (= (:r1 frame2') 50))
           (t/is (= (:r1 frame3') 50))
           ;; Shapes without test-token-2 are never decoded
           (t/is (string? (omap/get-encoded objects (cthi/id :frame4))))
           (t/is (string? (omap/get-encoded objects (cthi/id :frame1))))))
       tohs/stop-on-undo-commit))))

(def ^:private sample-position-data
  [{:x 0 :y 0 :width 10 :height 10 :fills [] :text "text"}])

(defn- setup-file-with-texts-on-other-page
  "Adds a second page with texts that hold position data:
  - :frame-a holds :text-a, with a font size token, and :text-b.
  - :row, a flex row, holds :grown, resized by a token, and :text-c,
    which it moves.
  - :fill-row, a flex row resized by a token, holds :text-e, which fills
    its width.
  - :frame-c holds :text-d.
  The first page stays the current one."
  []
  (let [make-token    (fn [lib label type value]
                        (ctob/add-token lib (cthi/id :test-token-set)
                                        (ctob/make-token :id (cthi/new-id! label)
                                                         :name (name label)
                                                         :type type
                                                         :value value)))
        file          (ctht/sample-file-with-tokens
                       :lib-fn #(-> %
                                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                                       :name "test-token-set"))
                                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :test-theme)
                                                                           :name "test-theme"
                                                                           :sets #{"test-token-set"}))
                                    (make-token :token-font-size :font-size 20)
                                    (make-token :token-width :dimensions 80)
                                    (make-token :token-row-width :dimensions 200))
                       :status-fn #(ctos/set-tokens-status % #{(cthi/id :test-theme)} #{(cthi/id :test-token-set)}))
        first-page-id (cthf/current-page-id file)
        row           {:type :frame :layout :flex :layout-flex-dir :row
                       :layout-item-h-sizing :fix :layout-item-v-sizing :fix
                       :layout-padding-type :multiple
                       :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}}
        add-text      (fn [file label parent-label]
                        (-> file
                            (ctho/add-text label "text" :text-params {:parent-label parent-label})
                            (cths/update-shape label :position-data sample-position-data)))]
    (-> file
        (cthf/add-sample-page :page2)
        (ctho/add-frame :frame-a)
        (add-text :text-a :frame-a)
        (add-text :text-b :frame-a)
        (cths/add-sample-shape :row (merge row {:x 0 :y 200 :width 400 :height 100}))
        (cths/add-sample-shape :grown :parent-label :row :x 0 :y 200 :width 50 :height 20)
        (add-text :text-c :row)
        (cths/add-sample-shape :fill-row (merge row {:x 0 :y 400 :width 100 :height 100}))
        (add-text :text-e :fill-row)
        (cths/update-shape :text-e :layout-item-h-sizing :fill)
        (ctho/add-frame :frame-c)
        (add-text :text-d :frame-c)
        (ctht/apply-token-to-shape :text-a "token-font-size" [:font-size] [] nil)
        (ctht/apply-token-to-shape :grown "token-width" [:width] [] nil)
        (ctht/apply-token-to-shape :fill-row "token-row-width" [:width] [] nil)
        (vary-meta assoc :current-page-id first-page-id))))

(t/deftest propagation-resets-only-resized-texts-on-other-pages
  (t/async
    done
    (let [;; ==== Setup
          file  (setup-file-with-texts-on-other-page)
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               shape         #(cths/get-shape file' % :page-label :page2)
               position-data #(:position-data (shape %))]

           ;; ==== Check
           ;; The text with a token update
           (t/is (nil? (position-data :text-a)))
           ;; A text next to a shape with a paint-only update
           (t/is (= sample-position-data (position-data :text-b)))
           ;; A text the layout only moves keeps its position data, moved
           (t/is (mth/close? (:width (shape :grown)) 80))
           (t/is (= (count sample-position-data) (count (position-data :text-c))))
           ;; A text the layout resizes
           (t/is (mth/close? (:width (shape :fill-row)) 200))
           (t/is (nil? (position-data :text-e)))
           ;; A text without any update around
           (t/is (= sample-position-data (position-data :text-d)))))))))

(t/deftest token-edit-propagates-only-the-edited-token
  (t/async
    done
    (let [;; ==== Setup
          file  (setup-file-with-stale-values)
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwtl/set-selected-token-set-id (cthi/id :test-token-set))
                  (dwtl/update-token (cthi/id :test-token-2)
                                     {:name "test-token-2"
                                      :type :border-radius
                                      :value 66})
                  ;; Built now, from the state before the edit
                  (dwtp/propagate-workspace-tokens {:previous-tokens (dwta/active-tokens @store)})]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame2' (cths/get-shape file' :frame2)
               rect1'  (cths/get-shape file' :rect1)]

           ;; ==== Check
           (t/is (= (:r1 frame2') 66))
           ;; test-token-3 was not edited, so its stale shape is untouched
           (t/is (= (:r1 rect1') 10))))))))

(t/deftest propagation-waits-for-the-previous-one
  (t/async
    done
    (let [;; ==== Setup
          file    (setup-file-with-stale-values)
          store   (ths/setup-store file)
          emitted (atom [])
          _       (rx/subs! #(swap! emitted conj (ptk/type %))
                            (ptk/input-stream store))

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens)
                  (dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'    (ths/get-file-from-state new-state)
               types    @emitted
               finished (keep-indexed #(when (= %2 ::dwtp/propagation-finished) %1) types)
               chunks   (keep-indexed #(when (= %2 ::dwtp/propagate-scope) %1) types)]

           ;; ==== Check
           (t/is (= 2 (count finished)))
           ;; The second run starts its pages after the first one finished
           (t/is (< (first finished) (last chunks)))
           (t/is (< (last chunks) (last finished)))
           (t/is (= (:r1 (cths/get-shape file' :frame3)) 50))))))))

(t/deftest token-change-commits-at-once-and-undoes-with-the-current-page
  (t/async
    done
    (let [;; ==== Setup
          file    (setup-file-with-stale-values)
          store   (ths/setup-store file)
          commits (tohs/watch-commits store)
          token   #(ctob/get-token (ctht/get-tokens-lib (ths/get-file-from-state %))
                                   (cthi/id :test-token-set) (cthi/id :test-token-2))
          ;; The state right after the change, before its propagation runs
          at-once (volatile! nil)

          ;; ==== Action
          events [(dwtl/set-selected-token-set-id (cthi/id :test-token-set))
                  (dwtl/update-token (cthi/id :test-token-2)
                                     {:name "test-token-2"
                                      :type :border-radius
                                      :value 66})
                  (ptk/reify ::read-state
                    ptk/UpdateEvent
                    (update [_ state]
                      (vreset! at-once state)
                      state))]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'       (ths/get-file-from-state new-state)
               [token-commit & page-commits] @commits
               page-commit (d/seek (fn [commit]
                                     (some #(and (= :mod-obj (:type %)) (= (cthi/id :frame2) (:id %)))
                                           (:redo-changes commit)))
                                   page-commits)]

           ;; ==== Check
           (t/is (= 66 (:value (token @at-once))) "the token changes at once")
           (t/is (= [:set-token] (map :type (:redo-changes token-commit))) "in a commit of its own")
           (t/is (some? page-commit) "the current page follows")
           (t/is (= (:undo-group token-commit) (:undo-group page-commit)) "in the same undo step")
           (t/is (= (:r1 (cths/get-shape file' :frame2)) 66))
           ;; :rect1 applies an unchanged token, so it is left as it is
           (t/is (= (:r1 (cths/get-shape file' :rect1)) 10))))))))

(t/deftest propagation-sets-only-out-of-date-attributes
  (t/async
    done
    (let [;; ==== Setup
          file    (-> (setup-file-with-stale-values)
                      ;; :frame3 already holds the value the token will have
                      (cths/update-shape :frame3 :r1 66)
                      (cths/update-shape :frame3 :r2 66)
                      (cths/update-shape :frame3 :r3 66)
                      (cths/update-shape :frame3 :r4 66)
                      (cths/update-shape :frame3 :token-values {:r1 66 :r2 66 :r3 66 :r4 66}))
          store   (ths/setup-store file)
          updated (atom #{})

          ;; ==== Action
          events [(dwtl/set-selected-token-set-id (cthi/id :test-token-set))
                  (dwtl/update-token (cthi/id :test-token-2)
                                     {:name "test-token-2"
                                      :type :border-radius
                                      :value 66})]]

      (rx/subs! (fn [event]
                  (swap! updated into
                         (comp (filter #(= :mod-obj (:type %)))
                               (map :id))
                         (:redo-changes (deref event))))
                (rx/filter dch/commit? (ptk/input-stream store)))

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file' (ths/get-file-from-state new-state)]

           ;; ==== Check
           (t/is (= (:r1 (cths/get-shape file' :frame2)) 66))
           (t/is (contains? @updated (cthi/id :frame2)))
           (t/is (not (contains? @updated (cthi/id :frame3))))))))))

(t/deftest unassigned-values-are-set-once
  (t/async
    done
    (let [;; ==== Setup
          file    (setup-file-with-stale-values)
          store   (ths/setup-store file)
          commits (tohs/watch-commits store)

          ;; ==== Action
          events [(dwtp/propagate-workspace-tokens)
                  (dwtp/propagate-workspace-tokens)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'   (ths/get-file-from-state new-state)
               frame2' (cths/get-shape file' :frame2)
               writes  (filter (fn [changes]
                                 (some #(and (= :mod-obj (:type %)) (= (cthi/id :frame2) (:id %)))
                                       changes))
                               (map :redo-changes @commits))]

           ;; ==== Check
           (t/is (= (:r1 frame2') 50))
           (t/is (= {:r1 50 :r2 50 :r3 50 :r4 50} (:token-values frame2')))
           (t/is (= 1 (count writes)) "the second run finds nothing to set")))))))

(t/deftest remap-keeps-the-values
  (t/async
    done
    (let [;; ==== Setup
          file   (-> (setup-file-with-stale-values)
                     (cths/update-shape :frame2 :token-values {:r1 10 :r2 10 :r3 10 :r4 10}))
          store  (ths/setup-store file)

          ;; ==== Action
          events [(remap/remap-tokens "test-token-2" "test-token-renamed")]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               frame2' (cths/get-shape (ths/get-file-from-state new-state) :frame2)]

           ;; ==== Check
           (t/is (= "test-token-renamed" (get-in frame2' [:applied-tokens :r1])))
           (t/is (= {:r1 10 :r2 10 :r3 10 :r4 10} (:token-values frame2')))))))))

(t/deftest reference-count-matches-a-full-scan
  (let [file      (-> (setup-file-with-stale-values)
                      (ctho/add-frame :frame4)
                      (encode-page-objects))
        file-data (:data file)
        page      (cthf/current-page file)
        expected  (->> (vals (:objects page))
                       (mapcat #(vals (:applied-tokens %)))
                       (filter #(= % "test-token-2"))
                       (count))]
    (t/is (= 8 expected))
    (t/is (= expected (remap/count-token-references file-data "test-token-2")))))

(defn- setup-file-with-stale-components
  "Adds :component1 (from :frame1) and :component2 (from :frame2), and a
  plain :frame3, all of them with a stored radius that differs from the
  resolved token value."
  []
  (-> (setup-base-file)
      (ctht/apply-token-to-shape :frame1 "test-token-1" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
      (cthc/make-component :component1 :frame1)
      (ctho/add-frame :frame2)
      (ctht/apply-token-to-shape :frame2 "test-token-2" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
      (cthc/make-component :component2 :frame2)
      (ctho/add-frame :frame3)
      (ctht/apply-token-to-shape :frame3 "test-token-3" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)))

(t/deftest instantiate-component-applies-token-values
  (t/async
    done
    (let [;; ==== Setup
          file  (setup-file-with-stale-components)
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwl/instantiate-component (:id file)
                                             (cthi/id :component1)
                                             (geom/point 0 0))]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               copy'   (dsh/lookup-shape new-state (first (dsh/lookup-selected new-state)))
               file'   (ths/get-file-from-state new-state)
               frame3' (cths/get-shape file' :frame3)]

           ;; ==== Check
           (t/is (= (get-in copy' [:applied-tokens :r1]) "test-token-1"))
           (t/is (= (:r1 copy') 25))
           (t/is (= (:r4 copy') 25))
           (t/is (= (:r1 frame3') 10))))))))

(t/deftest component-swap-applies-token-values
  (t/async
    done
    (let [;; ==== Setup
          file  (-> (setup-file-with-stale-components)
                    (cthc/instantiate-component :component1 :c-frame1))
          store (ths/setup-store file)

          ;; ==== Action
          events [(dwl/component-swap (cths/get-shape file :c-frame1)
                                      (:id file)
                                      (cthi/id :component2)
                                      true)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [;; ==== Get
               copy'   (dsh/lookup-shape new-state (first (dsh/lookup-selected new-state)))
               file'   (ths/get-file-from-state new-state)
               frame3' (cths/get-shape file' :frame3)]

           ;; ==== Check
           (t/is (= (:component-id copy') (cthi/id :component2)))
           (t/is (= (get-in copy' [:applied-tokens :r1]) "test-token-2"))
           (t/is (= (:r1 copy') 50))
           (t/is (= (:r4 copy') 50))
           (t/is (= (:r1 frame3') 10))))))))
