;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.exports-files-test
  "Tests for opening the `.penpot` download dialog: the pending changes are
  sent first, and the dialog is told when some are still not saved."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.exports.files :as fexp]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dps]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- file-param
  [file-id]
  {:id file-id :name "File" :project-id (uuid/next) :is-shared false})

(defn- with-queued-change
  "Adds to `state` a save queue that still holds a change of `file-id`."
  [state file-id]
  (let [commit-id (uuid/next)]
    (assoc state :persistence {:queue (conj #queue [] commit-id)
                               :index {commit-id {:id commit-id :file-id file-id}}})))

(defn- ^:async dialog-props
  "Opens the download dialog for `file-id` from `state` and returns the
  props the dialog is shown with, plus the file ids the save wait got."
  [state file-id]
  (let [store  (ptk/store {:state state :on-error #(t/is false (str %))})
        waited (atom nil)]
    (await
     (mock/with-mocks*
       {rp/cmd!                          (mock/stub (fn [_ _] (rx/of false)))
        dps/force-persist-files-and-wait (mock/stub (fn [file-ids _]
                                                      (reset! waited file-ids)
                                                      (rx/empty)))}
       (ptk/emit! store (fexp/open-export-dialog [(file-param file-id)]))
       (await (async/wait-for #(some? (get-in @store [::modal/modal :props]))
                              "the dialog opens"))))
    (assoc (get-in @store [::modal/modal :props]) ::waited @waited)))

;; Scenario: the user downloads a file whose changes are all saved. Proves:
;; the pending changes of that file are sent first, and the dialog shows no
;; warning.
(t/deftest ^:async download-of-a-saved-file-has-no-warning
  (let [file-id (uuid/next)
        props   (await (dialog-props {} file-id))]
    (t/is (= #{file-id} (::waited props)) "the save wait covers the downloaded file")
    (t/is (false? (:unsaved props)))))

;; Scenario: the file still has a change the server does not have after the
;; wait. Proves: the dialog is told, so it can warn that the download will
;; not include it.
(t/deftest ^:async download-of-a-file-with-unsaved-changes-warns
  (let [file-id (uuid/next)
        props   (await (dialog-props (with-queued-change {} file-id) file-id))]
    (t/is (true? (:unsaved props)))))

;; Scenario: another file has unsaved changes. Proves: only the downloaded
;; file's changes count.
(t/deftest ^:async unsaved-changes-of-another-file-do-not-warn
  (let [file-id (uuid/next)
        props   (await (dialog-props (with-queued-change {} (uuid/next)) file-id))]
    (t/is (false? (:unsaved props)))))
