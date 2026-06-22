;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-interactions-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.interactions :as dwi]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-file
  "A file with two boards (frames)."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :board1 :name "Board 1")
      (ctho/add-frame :board2 :name "Board 2")))

(defn- navigate-interaction
  "A navigate interaction from `origin-id` to `dest-id` (a flow origin)."
  [origin-id dest-id]
  (-> ctsi/default-interaction
      (ctsi/set-destination dest-id)
      (assoc :position-relative-to origin-id)))

(defn- page-flows
  "The flows map of the current page in `state`."
  [state]
  (let [file-id (:current-file-id state)
        page-id (:current-page-id state)]
    (get-in state [:files file-id :data :pages-index page-id :flows])))

;; ---------------------------------------------------------------------------
;; add-interaction (plugin API path): implicit flow creation must match the
;; editor behavior of creating a flow when an interaction is added outside of
;; any existing flow.
;; ---------------------------------------------------------------------------

(t/deftest add-interaction-creates-implicit-flow-outside-flow
  (t/async
    done
    (let [file        (make-file)
          board1-id   (:id (cths/get-shape file :board1))
          board2-id   (:id (cths/get-shape file :board2))
          page-id     (cthf/current-page-id file)
          interaction (navigate-interaction board1-id board2-id)
          store       (ths/setup-store file)
          events      [(dwi/add-interaction page-id board1-id interaction)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [flows (vals (page-flows new-state))]
           (t/is (= 1 (count flows))
                 "an implicit flow is created for the origin board")
           (t/is (= board1-id (:starting-frame (first flows)))
                 "the implicit flow starts at the origin board")))))))

(t/deftest add-interaction-does-not-duplicate-flow-for-same-frame
  (t/async
    done
    (let [file        (make-file)
          board1-id   (:id (cths/get-shape file :board1))
          board2-id   (:id (cths/get-shape file :board2))
          page-id     (cthf/current-page-id file)
          flow-id     (uuid/next)
          ;; board1 is already the starting frame of an explicit flow
          file        (assoc-in file
                                [:data :pages-index page-id :flows flow-id]
                                {:id flow-id
                                 :name "My flow"
                                 :starting-frame board1-id})

          interaction (navigate-interaction board1-id board2-id)
          store       (ths/setup-store file)
          events      [(dwi/add-interaction page-id board1-id interaction)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [flows (vals (page-flows new-state))]
           (t/is (= 1 (count flows))
                 "no duplicate flow is created when the frame is already in a flow")
           (t/is (= "My flow" (:name (first flows)))
                 "the existing explicit flow is preserved")))))))

(t/deftest add-interaction-without-destination-does-not-create-flow
  (t/async
    done
    (let [file        (make-file)
          board1-id   (:id (cths/get-shape file :board1))
          page-id     (cthf/current-page-id file)
          ;; An open-url interaction is not a flow origin (no destination)
          interaction (-> ctsi/default-interaction
                          (assoc :action-type :open-url
                                 :url "https://example.com"
                                 :destination nil))
          store       (ths/setup-store file)
          events      [(dwi/add-interaction page-id board1-id interaction)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (t/is (empty? (page-flows new-state))
               "non flow-origin interactions do not create a flow"))))))
