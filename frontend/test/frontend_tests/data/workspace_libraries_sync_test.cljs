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
   [app.main.data.workspace :as-alias dw]
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
  "A store with the open file `file-id` and its libraries, last synced at
   `synced-at`."
  [file-id & library-ids]
  (ptk/store {:state {:current-file-id file-id
                      :permissions {:can-edit true}
                      :files (into {file-id {:id file-id
                                             :data (ctf/make-file-data file-id)}}
                                   (map (fn [library-id]
                                          [library-id {:id library-id
                                                       :library-of file-id
                                                       :synced-at synced-at
                                                       :modified-at synced-at
                                                       :data (ctf/make-file-data library-id)}]))
                                   library-ids)}
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

;; The rejection can arrive while the sync emits its changes. Even when
;; the queue settles, the live model may still be in the preview backup.
(t/deftest ^:async a-fast-rejection-restores-the-sync-date-but-waits-for-live-rollback
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        undo-group (uuid/next)
        other-file (uuid/next)
        store      (library-store file-id library-id)
        calls      (atom [])
        targets    {:file-ids #{file-id} :undo-groups #{undo-group}}]
    (await
     (mock/with-mocks*
       {rx/timer             (mock/stub (fn [& _] (rx/of 0)))
        dps/wait-persisted   (mock/stub (fn [& _] (rx/observe-on :async (rx/of :saved))))
        dwl/notify-sync-file (mock/stub (fn [] #(update % ::notices (fnil inc 0))))
        rp/cmd!              (mock/stub (fn [cmd _]
                                          (swap! calls conj cmd)
                                          (rx/of nil)))}
       (->> (ptk/input-stream store)
            (rx/filter (ptk/type? ::dwl/set-updating-library))
            (rx/take 1)
            (rx/subs! (fn [_]
                        (ptk/emit! store
                                   #(assoc-in % [:workspace-global :preview-id] (uuid/next))
                                   (ptk/data-event ::dps/save-rejected targets)))))
       (ptk/emit! store (dwl/sync-file file-id library-id nil nil undo-group))
       (await (async/settle))

       (t/is (= synced-at (get-in @store [:files library-id :synced-at])))
       (t/is (empty? @calls) "a settled queue does not make the rejected sync successful")
       (t/is (nil? (::notices @store)) "the rejection alone does not check the notice")

       (ptk/emit! store
                  (ptk/data-event ::dps/rolled-back (assoc targets :undo-groups #{(uuid/next)}))
                  (ptk/data-event ::dps/rolled-back (assoc targets :file-ids #{other-file})))
       (await (async/settle))
       (t/is (nil? (::notices @store)) "unrelated rollbacks do not check the notice")

       (ptk/emit! store
                  #(update % :workspace-global dissoc :preview-id)
                  (ptk/data-event ::dps/rolled-back targets))
       (await (async/wait-for-store store #(= 1 (::notices @store)) "the live rollback checks the notice"))
       (ptk/emit! store (ptk/data-event ::dps/rolled-back targets))
       (await (async/settle))
       (t/is (= 1 (::notices @store)) "the notice is checked once")))))

(t/deftest ^:async rejection-of-a-multi-file-group-does-not-cancel-another-library-sync
  (let [file-id    (uuid/next)
        other-file (uuid/next)
        library-a  (uuid/next)
        library-b  (uuid/next)
        group-a    (uuid/next)
        store      (library-store file-id library-a library-b)
        saved      (rx/subject)
        calls      (atom [])]
    (await
     (mock/with-mocks*
       {rx/timer           (mock/stub (fn [& _] (rx/of 0)))
        dps/wait-persisted (mock/stub (fn [& _] (rx/observe-on :async saved)))
        rp/cmd!            (mock/stub (fn [cmd params]
                                        (swap! calls conj [cmd params])
                                        (rx/of nil)))}
       (ptk/emit! store (dwl/sync-file file-id library-a nil nil group-a)
                  (dwl/sync-file file-id library-b))
       (let [date-a (get-in @store [:files library-a :synced-at])
             date-b (get-in @store [:files library-b :synced-at])]
         (ptk/emit! store (ptk/data-event ::dps/save-rejected
                                          {:file-ids #{other-file} :undo-groups #{group-a}}))
         (await (async/settle))
         (t/is (not= synced-at date-a))
         (t/is (= date-a (get-in @store [:files library-a :synced-at]))
               "a matching group in another file does not cancel the sync")

         (ptk/emit! store (ptk/data-event ::dps/save-rejected
                                          {:file-ids #{other-file file-id} :undo-groups #{group-a}}))
         (rx/push! saved :saved)
         (rx/end! saved)
         (await (async/settle))
         (t/is (= synced-at (get-in @store [:files library-a :synced-at])))
         (t/is (not= synced-at date-b))
         (t/is (= date-b (get-in @store [:files library-b :synced-at])))
         (t/is (= [[:update-file-library-sync-status {:file-id file-id :library-id library-b}]]
                  @calls)))
       (rx/dispose! store)))))

(t/deftest ^:async a-new-workspace-opening-does-not-inherit-a-rejected-sync-notice
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        undo-group (uuid/next)
        store      (library-store file-id library-id)
        targets    {:file-ids #{file-id} :undo-groups #{undo-group}}]
    (await
     (mock/with-mocks*
       {rx/timer             (mock/stub (fn [& _] (rx/of 0)))
        dps/wait-persisted   (mock/stub (fn [& _] (rx/observe-on :async (rx/of :saved))))
        dwl/notify-sync-file (mock/stub (fn [] #(update % ::notices (fnil inc 0))))
        rp/cmd!              (mock/stub (fn [_ _] (rx/of nil)))}
       (ptk/emit! store (dwl/sync-file file-id library-id nil nil undo-group)
                  (ptk/data-event ::dps/save-rejected targets))
       (await (async/settle))
       (t/is (= synced-at (get-in @store [:files library-id :synced-at])))

       (ptk/emit! store (ptk/data-event ::dw/initialize-workspace nil)
                  (ptk/data-event ::dps/rolled-back targets))
       (await (async/settle))
       (t/is (nil? (::notices @store)))))))

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
