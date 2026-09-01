;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.logic.token-libraries-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.tokens.application :as dwta]
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

(defn setup-file []
  (-> (cthf/sample-file :file-1 :page-label :page-1)
      (cths/add-sample-shape :rect-1
                             :type :rect
                             :r1 0 :r2 0 :r3 0 :r4 0)))

(defn setup-file-with-tokens
  []
  (-> (ctht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-a)
                                                       :name "Set A"))
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-b)
                                                       :name "Set B"))
                    (ctob/add-token (cthi/id :set-a)
                                    (ctob/make-token {:name "borderRadius.sm"
                                                      :value "12"
                                                      :type :border-radius}))
                    (ctob/add-token (cthi/id :set-b)
                                    (ctob/make-token {:name "borderRadius.sm"
                                                      :value "24"
                                                      :type :border-radius})))
       :status-fn #(ctos/set-tokens-status % #{} #{(cthi/id :set-a)}))))

(t/deftest test-apply-token-from-lib
  (t/testing "applies token from library to local shape updates shape attributes to resolved value"
    (t/async
      done
      (let [library (setup-file-with-tokens)
            file    (setup-file)
            store   (ths/setup-store file {:libraries [library]
                                           :tokens-source-library library})
            rect-1  (cths/get-shape file :rect-1)
            token   (toht/get-token library "borderRadius.sm")
            events  [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                        :attributes #{:r1 :r2 :r3 :r4}
                                        :token token
                                        :on-update-shape dwta/update-shape-radius})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'   (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)]

             (t/testing "shape `:applied-tokens` got updated"
               (t/is (some? (:applied-tokens rect-1')))
               (t/is (= (:r1 (:applied-tokens rect-1')) (:name token))))

             (t/testing "shape radius got update to the resolved token value."
               (t/is (= (:r1 rect-1') 12)))))))))

  (t/testing "applies token using active set-b when the file's active token set changes"
    (t/async
      done
      (let [library (setup-file-with-tokens)
            file    (-> (setup-file)
                        (ctht/set-tokens-source library)
                        (ctht/update-tokens-status #(ctos/set-tokens-status % #{} #{(cthi/id :set-b)})))
            store   (ths/setup-store file {:libraries [library]})
            rect-1  (cths/get-shape file :rect-1)
            token   (toht/get-token library "borderRadius.sm")
            events  [(dwta/apply-token {:shape-ids [(:id rect-1)]
                                        :attributes #{:r1 :r2 :r3 :r4}
                                        :token token
                                        :on-update-shape dwta/update-shape-radius})]]
        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'   (ths/get-file-from-state new-state)
                 rect-1' (cths/get-shape file' :rect-1)]
             (t/testing "shape radius uses the value from the active set-b"
               (t/is (= (:r1 rect-1') 24))))))))))
