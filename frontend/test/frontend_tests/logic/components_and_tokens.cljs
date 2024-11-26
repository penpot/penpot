;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns frontend-tests.logic.components-and-tokens
  (:require
   [app.common.geom.point :as geom]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.update :as wtu]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn- setup-base-file
  []
  (-> (cthf/sample-file :file1)
      (ctht/add-tokens-lib)
      (ctht/update-tokens-lib #(-> %
                                   (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                                   (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                          :sets #{"test-token-set"}))
                                   (ctob/set-active-themes #{"/test-theme"})
                                   (ctob/add-token-in-set "test-token-set"
                                                          (ctob/make-token :name "test-token-1"
                                                                           :type :border-radius
                                                                           :value 25))
                                   (ctob/add-token-in-set "test-token-set"
                                                          (ctob/make-token :name "test-token-2"
                                                                           :type :border-radius
                                                                           :value 50))
                                   (ctob/add-token-in-set "test-token-set"
                                                          (ctob/make-token :name "test-token-3"
                                                                           :type :border-radius
                                                                           :value 75))))
      (ctho/add-frame :frame1)
      (ctht/apply-token-to-shape :frame1 "test-token-1" [:rx :ry] [:rx :ry] 25)))

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
              file'          (ths/get-file-from-store new-state)
              frame1'        (cths/get-shape file' :frame1)
              tokens-frame1' (:applied-tokens frame1')]

          ;; ==== Check
          (t/is (= (count tokens-frame1') 2))
          (t/is (= (get tokens-frame1' :rx) "test-token-1"))
          (t/is (= (get tokens-frame1' :ry) "test-token-1"))
          (t/is (= (get frame1' :rx) 25))
          (t/is (= (get frame1' :ry) 25))))))))

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
              selected       (wsh/lookup-selected new-state)
              c-frame1'      (wsh/lookup-shape new-state (first selected))
              tokens-frame1' (:applied-tokens c-frame1')]

          ;; ==== Check
          (t/is (= (count tokens-frame1') 2))
          (t/is (= (get tokens-frame1' :rx) "test-token-1"))
          (t/is (= (get tokens-frame1' :ry) "test-token-1"))
          (t/is (= (get c-frame1' :rx) 25))
          (t/is (= (get c-frame1' :ry) 25))))))))

(t/deftest change-token-in-main
  (t/async
   done
   (let [;; ==== Setup
         file     (setup-file-with-copy)
         store    (ths/setup-store file)

         ;; ==== Action
         events [(wtch/apply-token {:shape-ids [(cthi/id :frame1)]
                                    :attributes #{:rx :ry}
                                    :token (toht/get-token file "test-token-2")
                                    :on-update-shape wtch/update-shape-radius-all})]

         step2 (fn [_]
                 (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                   (ths/run-store
                    store done events2
                    (fn [new-state]
                      (let [;; ==== Get
                            file'          (ths/get-file-from-store new-state)
                            c-frame1'      (cths/get-shape file' :c-frame1)
                            tokens-frame1' (:applied-tokens c-frame1')]

                        ;; ==== Check
                        (t/is (= (count tokens-frame1') 2))
                        (t/is (= (get tokens-frame1' :rx) "test-token-2"))
                        (t/is (= (get tokens-frame1' :ry) "test-token-2"))
                        (t/is (= (get c-frame1' :rx) 50))
                        (t/is (= (get c-frame1' :ry) 50)))))))]

     (tohs/run-store-async
      store step2 events identity))))

(t/deftest remove-token-in-main
  (t/async
   done
   (let [;; ==== Setup
         file     (setup-file-with-copy)
         store    (ths/setup-store file)

         ;; ==== Action
         events [(wtch/unapply-token {:shape-ids [(cthi/id :frame1)]
                                      :attributes #{:rx :ry}
                                      :token (toht/get-token file "test-token-1")})]

         step2 (fn [_]
                 (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                   (ths/run-store
                    store done events2
                    (fn [new-state]
                      (let [;; ==== Get
                            file'          (ths/get-file-from-store new-state)
                            c-frame1'      (cths/get-shape file' :c-frame1)
                            tokens-frame1' (:applied-tokens c-frame1')]

                        ;; ==== Check
                        (t/is (= (count tokens-frame1') 0))
                        (t/is (= (get c-frame1' :rx) 25))
                        (t/is (= (get c-frame1' :ry) 25)))))))]

     (tohs/run-store-async
      store step2 events identity))))

(t/deftest modify-token
  (t/async
   done
   (let [;; ==== Setup
         file     (setup-file-with-copy)
         store    (ths/setup-store file)

         ;; ==== Action
         events [(dt/update-create-token {:token (ctob/make-token :name "test-token-1"
                                                                  :type :border-radius
                                                                  :value 66)
                                          :prev-token-name "test-token-1"})]

         step2 (fn [_]
                 (let [events2 [(wtu/update-workspace-tokens)
                                (dwl/sync-file (:id file) (:id file))]]
                   (tohs/run-store-async
                    store done events2
                    (fn [new-state]
                      (let [;; ==== Get
                            file'          (ths/get-file-from-store new-state)
                            c-frame1'      (cths/get-shape file' :c-frame1)
                            tokens-frame1' (:applied-tokens c-frame1')]

                        ;; ==== Check
                        (t/is (= (count tokens-frame1') 2))
                        (t/is (= (get tokens-frame1' :rx) "test-token-1"))
                        (t/is (= (get tokens-frame1' :ry) "test-token-1"))
                        (t/is (= (get c-frame1' :rx) 66))
                        (t/is (= (get c-frame1' :ry) 66)))))))]

     (tohs/run-store-async
      store step2 events identity))))

(t/deftest change-token-in-copy-then-change-main
  (t/async
   done
   (let [;; ==== Setup
         file     (setup-file-with-copy)
         store    (ths/setup-store file)

         ;; ==== Action
         events [(wtch/apply-token {:shape-ids [(cthi/id :c-frame1)]
                                    :attributes #{:rx :ry}
                                    :token (toht/get-token file "test-token-2")
                                    :on-update-shape wtch/update-shape-radius-all})
                 (wtch/apply-token {:shape-ids [(cthi/id :frame1)]
                                    :attributes #{:rx :ry}
                                    :token (toht/get-token file "test-token-3")
                                    :on-update-shape wtch/update-shape-radius-all})]

         step2 (fn [_]
                 (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                   (ths/run-store
                    store done events2
                    (fn [new-state]
                      (let [;; ==== Get
                            file'          (ths/get-file-from-store new-state)
                            c-frame1'      (cths/get-shape file' :c-frame1)
                            tokens-frame1' (:applied-tokens c-frame1')]

                        ;; ==== Check
                        (t/is (= (count tokens-frame1') 2))
                        (t/is (= (get tokens-frame1' :rx) "test-token-2"))
                        (t/is (= (get tokens-frame1' :ry) "test-token-2"))
                        (t/is (= (get c-frame1' :rx) 50))
                        (t/is (= (get c-frame1' :ry) 50)))))))]

     (tohs/run-store-async
      store step2 events identity))))

(t/deftest remove-token-in-copy-then-change-main
  (t/async
   done
   (let [;; ==== Setup
         file     (setup-file-with-copy)
         store    (ths/setup-store file)

         ;; ==== Action
         events [(wtch/unapply-token {:shape-ids [(cthi/id :c-frame1)]
                                      :attributes #{:rx :ry}
                                      :token (toht/get-token file "test-token-1")})
                 (wtch/apply-token {:shape-ids [(cthi/id :frame1)]
                                    :attributes #{:rx :ry}
                                    :token (toht/get-token file "test-token-3")
                                    :on-update-shape wtch/update-shape-radius-all})]

         step2 (fn [_]
                 (let [events2 [(dwl/sync-file (:id file) (:id file))]]
                   (ths/run-store
                    store done events2
                    (fn [new-state]
                      (let [;; ==== Get
                            file'          (ths/get-file-from-store new-state)
                            c-frame1'      (cths/get-shape file' :c-frame1)
                            tokens-frame1' (:applied-tokens c-frame1')]

                        ;; ==== Check
                        (t/is (= (count tokens-frame1') 0))
                        (t/is (= (get c-frame1' :rx) 25))
                        (t/is (= (get c-frame1' :ry) 25)))))))]

     (tohs/run-store-async
      store step2 events identity))))
