;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-libraries-sync-test
  "Tests for marking a library as synced after `dwl/sync-file`."
  (:require
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private synced-at
  (ct/inst 1000))

(defn- library-store
  "A store with the open file `file-id` and its library `library-id`, last
  synced at `synced-at`."
  [file-id library-id]
  (ptk/store {:state {:current-file-id file-id
                      :permissions {:can-edit true}
                      :files {file-id {:id file-id
                                       :data (ctf/make-file-data file-id)}
                              library-id {:id library-id
                                          :library-of file-id
                                          :synced-at synced-at
                                          :modified-at synced-at
                                          :data (ctf/make-file-data library-id)}}}
              :on-error #(t/is false (str %))}))

(defn- ^:async sync-library
  "Syncs the library and lets the save end with `saved`, an observable for
  `dps/wait-persisted`; `before-save` runs once the sync started. Waits until
  `settled?`, given the commands sent and the library sync date, is true,
  then calls `check` with them, the store and the file ids, and awaits
  what it returns."
  [saved before-save settled? check]
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        store      (library-store file-id library-id)
        commands   (atom [])
        result     #(hash-map :commands @commands
                              :synced-at (get-in @store [:files library-id :synced-at])
                              :store store
                              :file-id file-id
                              :library-id library-id)]
    (await
     (mock/with-mocks*
       {rx/timer          (mock/stub (fn [& _] (rx/of 0)))
        dps/wait-persisted (mock/stub (fn [& _] saved))
        rp/cmd!           (mock/stub (fn [cmd _]
                                       (swap! commands conj cmd)
                                       (rx/of nil)))}
       (ptk/emit! store (dwl/sync-file file-id library-id))
       (await (async/wait-for #(not= synced-at (:synced-at (result)))
                              "the sync moves the sync date forward"))
       (before-save store file-id)
       (await (async/wait-for #(settled? (result)) "the sync settles"))
       (await (async/settle))
       (await (check (result)))))))

;; Scenario: the sync's commits are saved. Proves: the library is marked as
;; synced on the server.
(t/deftest ^:async a-saved-sync-marks-the-library-as-synced
  (await
   (sync-library (rx/of :saved)
                 (fn [_ _])
                 #(seq (:commands %))
                 (fn [{:keys [commands]}]
                   (t/is (= [:update-file-library-sync-status] commands))))))

;; Scenario: the server rejects the save of the sync's commits and the
;; rollback undoes them. Proves: the library is not marked as synced and its
;; sync date goes back, so the update notice can show again.
(t/deftest ^:async a-rejected-sync-leaves-the-library-to-sync-again
  (await
   (sync-library (rx/subject)
                 (fn [store file-id]
                   (ptk/emit! store (ptk/data-event ::dps/rolled-back
                                                    {:file-ids #{file-id}})))
                 #(= synced-at (:synced-at %))
                 (fn [{:keys [commands] :as result}]
                   (t/is (empty? commands))
                   (t/is (= synced-at (:synced-at result)))))))

;; Scenario: the save of the sync's commits fails for good, so waiting for
;; it ends with no value; later an edit of the same file is rejected.
;; Proves: the library is not marked as synced, its sync date goes back,
;; and the later rollback leaves the date alone.
(t/deftest ^:async a-failed-sync-save-ends-the-sync
  (let [saved (rx/subject)]
    (await
     (sync-library saved
                   ;; The save fails for good: the wait ends with no value
                   (fn [_ _] (rx/end! saved))
                   #(= synced-at (:synced-at %))
                   (^:async fn [{:keys [commands store file-id library-id]}]
                     (t/is (empty? commands))
                     (let [later (ct/inst 5000)]
                       (ptk/emit! store
                                  #(assoc-in % [:files library-id :synced-at] later)
                                  (ptk/data-event ::dps/rolled-back {:file-ids #{file-id}}))
                       (await (async/settle))
                       (t/is (= later (get-in @store [:files library-id :synced-at]))
                             "a later rollback does not wake the ended sync")))))))

;; Scenario: the user becomes a viewer right after "Update", so the save of
;; the sync's commits fails for good; when edit rights come back, the
;; release of the failed save undoes the sync. Proves: the update notice is
;; checked again, as after a rejected sync, instead of waiting for a reload.
(t/deftest ^:async a-released-sync-checks-the-update-notice-again
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        store      (library-store file-id library-id)
        waits      (atom 0)
        notified   (atom 0)]
    (await
     (mock/with-mocks*
       {rx/timer             (mock/stub (fn [& _] (rx/of 0)))
        ;; The first wait ends with the failed save, the next ones saved
        dps/wait-persisted   (mock/stub (fn [& _]
                                          (if (= 1 (swap! waits inc))
                                            (rx/empty)
                                            (rx/of :saved))))
        dwl/notify-sync-file (mock/stub (fn []
                                          (swap! notified inc)
                                          (ptk/data-event ::notified {})))
        rp/cmd!              (mock/stub (fn [_ _] (rx/of nil)))}
       (ptk/emit! store (dwl/sync-file file-id library-id))
       (await (async/wait-for #(= 1 @waits) "the save of the sync fails"))
       (await (async/settle))
       (t/is (= synced-at (get-in @store [:files library-id :synced-at])))
       (t/is (zero? @notified) "a failed save alone shows no notice")

       ;; The role change that brings edit rights back releases the save
       (ptk/emit! store (ptk/data-event ::dps/rolled-back {:file-ids #{file-id}}))
       (await (async/wait-for #(= 1 @notified) "the release checks the update notice"))))))
