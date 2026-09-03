;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.resize-debounce-opts-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.wasm-text :as dwwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-file []
  (-> (cthf/sample-file :file-1 :page-label :page-1)
      (ctho/add-text :text-1 "Derived")
      (ctho/add-text :text-2 "User edit")))

(defn- batch-opts
  [state]
  (get state :app.main.data.workspace.wasm-text/resize-wasm-text-debounce-opts))

(t/deftest derived-flag-does-not-leak-into-a-user-resize-in-the-same-batch
  (t/async
    done
    (let [file    (setup-file)
          store   (ths/setup-store file)
          text-1  (cths/get-shape file :text-1)
          text-2  (cths/get-shape file :text-2)
          ;; Same tick => both ids land in the same debounce batch, which is
          ;; what a font-load resize racing a sidebar resize produces.
          events  [(dwwt/resize-wasm-text-debounce (:id text-1) {:skip-component-sync? true})
                   (dwwt/resize-wasm-text-debounce (:id text-2) nil)]]
      (ths/run-store
       store done events
       (fn [state]
         ;; 3, not 2: the batch owner re-emits itself so text-1 is queued twice.
         (t/is (= 3 (count (get state :app.main.data.workspace.wasm-text/resize-wasm-text-debounce-ids))))
         (t/is (not (:skip-component-sync? (batch-opts state)))))))))

(t/deftest derived-only-batch-keeps-the-flag
  (t/async
    done
    (let [file    (setup-file)
          store   (ths/setup-store file)
          text-1  (cths/get-shape file :text-1)
          text-2  (cths/get-shape file :text-2)
          events  [(dwwt/resize-wasm-text-debounce (:id text-1) {:skip-component-sync? true})
                   (dwwt/resize-wasm-text-debounce (:id text-2) {:skip-component-sync? true})]]
      (ths/run-store
       store done events
       (fn [state]
         (t/is (true? (:skip-component-sync? (batch-opts state)))))))))
