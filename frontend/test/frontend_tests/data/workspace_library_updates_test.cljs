;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-library-updates-test
  "How a file learns that a linked library changed and offers to sync: the
   library change pushed by the server, the check of which libraries need a
   sync, the sync dialog and its update and dismiss actions, and the sync
   itself from an external library."
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.file :as ctf]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each ftm/fixture)

(def ^:private old-color "#111111")
(def ^:private new-color "#222222")

(def ^:private synced-at (js/Date. 1000))
(def ^:private component-changed-at (js/Date. 2000))
(def ^:private library-changed-at (js/Date. 3000))

(defn- library-and-file
  "A library whose component :lib-c changed after the file last synced
   with it, and a file with a copy of :lib-c. With `changed?`, the main
   child got a fill after the copy was made, so a sync changes the copy."
  [changed?]
  (let [library (-> (cthf/sample-file :library :is-shared true)
                    (ctho/add-simple-component :lib-c :lib-main :lib-main-child
                                               :child-params {:fills (cths/sample-fills-color
                                                                      :fill-color old-color)})
                    ;; `ctkl/update-component` would stamp the current date
                    (ctf/update-file-data
                     #(assoc-in % [:components (cthi/id :lib-c) :modified-at] component-changed-at))
                    (assoc :synced-at synced-at
                           :modified-at library-changed-at))
        file    (-> (cthf/sample-file :file1)
                    (cthc/instantiate-component :lib-c :copy
                                                :library library
                                                :children-labels [:copy-child])
                    (ctho/add-rect :rect))]
    [(cond-> library
       changed?
       (cths/update-shape :lib-main-child :fills (cths/sample-fills-color :fill-color new-color)))
     file]))

(defn- install-with-library!
  [changed?]
  (let [[library file] (library-and-file changed?)]
    (ftm/install! (-> (tm/make-situation file)
                      (tm/with-aux-files {(:id library) library})))
    library))

(defn- live-library
  []
  (get-in @st/state [:files (cthi/id :library)]))

(defn- copy-fill
  []
  (-> (cths/get-shape (ftm/current-file) :copy-child) :fills first :fill-color))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- await-until
  "Wait up to a second for `ready?`."
  [ready?]
  (ftm/await-step [] 1000 ready?))

(defn- notification
  []
  (:notification @st/state))

(defn- rpc-commands
  []
  (set (map :cmd @mock/rpc-calls)))

(def ^:private rpc-on-subscribe
  ;; An `rp/cmd!` double that records a call when its stream is subscribed,
  ;; as the real one sends the request only then.
  (mock/stub
   (fn [cmd params & _]
     (rx/defer (fn []
                 (swap! mock/rpc-calls conj {:cmd cmd :params params})
                 (rx/of nil))))))

(defn- await-sync-status-sent
  "Wait for the request that tells the server the file synced with the
   library, which `sync-file` sends after a delay."
  []
  (await-until #(contains? (rpc-commands) :update-file-library-sync-status)))

;; ----- Which libraries need a sync

(t/deftest assets-need-sync
  (let [[library file] (library-and-file true)
        data           (:data file)]
    (t/testing "a library changed after the last sync, with a used component changed too"
      (t/is (= [{:shape-id (cthi/id :copy) :asset-id (cthi/id :lib-c)
                 :asset-type :component :container-id (cthf/current-page-id file)}]
               (vec (dwl/assets-need-sync library data)))))
    (t/testing "the file itself"
      (t/is (nil? (dwl/assets-need-sync library (assoc data :id (:id library))))))
    (t/testing "a library not changed since the last sync"
      (t/is (nil? (dwl/assets-need-sync (assoc library :synced-at (js/Date. 4000)) data))))
    (t/testing "changes before the date the user dismissed the updates"
      (t/is (nil? (dwl/assets-need-sync library data (js/Date. 4000)))))
    (t/testing "a used component that did not change"
      (t/is (empty? (dwl/assets-need-sync (assoc library :synced-at (js/Date. 2500)) data))))))

;; ----- The sync dialog

(defn- sync-dialog-shown?
  []
  (= :sync-dialog (:tag (notification))))

(t/deftest ^:async sync-dialog-updates-the-copies
  (await
   (mock/with-mocks*
     {rp/cmd!   rpc-on-subscribe
      rx/timer  mock/timer-mock}
     (install-with-library! true)
     (t/is (= :settled (await (ftm/await-step [(dwl/notify-sync-file)] 1000 sync-dialog-shown?))))
     ((-> (notification) :accept :callback))
     (await (await-until #(= new-color (copy-fill))))
     (t/is (= new-color (copy-fill)))
     (t/is (not= :visible (:status (notification))))
     (t/is (some? (:synced-at (live-library))))
     (t/is (not= synced-at (:synced-at (live-library))))
     (t/is (= :settled (await (await-sync-status-sent)))))))

(t/deftest ^:async updating-a-library-propagates-the-file-tokens
  ;; the update of the sync dialog and of the libraries dialog's updates tab
  (await
   (mock/with-mocks*
     {rp/cmd!   rpc-on-subscribe
      rx/timer  mock/timer-mock}
     (install-with-library! true)
     (let [events (atom [])
           sub    (rx/sub! (->> st/stream
                                (rx/map ptk/type)
                                (rx/filter #{::dwl/sync-file ::dwtp/propagate-workspace-tokens}))
                           #(swap! events conj %))]
       (t/is (= :settled (await (ftm/await-step [(dwl/update-libraries (:id (ftm/current-file))
                                                                       [(:id (live-library))])]))))
       (await (await-until #(= new-color (copy-fill))))
       (rx/dispose! sub)
       (t/is (= [::dwl/sync-file ::dwtp/propagate-workspace-tokens] @events))))))

(t/deftest ^:async sync-dialog-dismiss-ignores-the-changes
  (await
   (mock/with-mocks*
     {rp/cmd! mock/rpc-cmd-mock}
     (install-with-library! true)
     (await (ftm/await-step [(dwl/notify-sync-file)] 1000 sync-dialog-shown?))
     ((-> (notification) :cancel :callback))
     (t/is (some? (:ignore-sync-until (ftm/current-file))))
     (t/is (contains? (rpc-commands) :ignore-file-library-sync-status))
     (t/is (= old-color (copy-fill)))
     (t/testing "no dialog for the same changes afterwards"
       (st/emit! (dwl/notify-sync-file))
       (await (sleep 50))
       (t/is (not (sync-dialog-shown?)))))))

(t/deftest ^:async no-sync-dialog-when-the-sync-changes-nothing
  (install-with-library! false)
  (st/emit! (dwl/notify-sync-file))
  (await (sleep 50))
  (t/is (not (sync-dialog-shown?))))

(defn- view-only
  []
  (ptk/reify ::view-only
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:permissions :can-edit] false))))

(t/deftest ^:async no-sync-dialog-without-edit-permission
  (install-with-library! true)
  (st/emit! (view-only) (dwl/notify-sync-file))
  (await (sleep 50))
  (t/is (not (sync-dialog-shown?))))

;; ----- Changes pushed by the server

(defn- rename-change
  [library]
  {:type :mod-obj
   :page-id (cthf/current-page-id library)
   :id (cthi/id :lib-main-child)
   :operations [{:type :set :attr :name :val "Renamed"}]})

(t/deftest ^:async library-change-from-the-server
  (await
   (mock/with-mocks*
     {dwl/fetch-library-thumbnails (mock/stub (fn [_] (ptk/data-event ::no-thumbnails)))}
     (let [library (install-with-library! true)]
       (await (ftm/await-step [(dwl/ext-library-changed (:id library) (js/Date. 5000) 7
                                                        [(rename-change library)])]))
       (let [library' (live-library)]
         (t/is (= 7 (:revn library')))
         (t/is (= (js/Date. 5000) (:modified-at library')))
         (t/is (= "Renamed" (:name (cths/get-shape library' :lib-main-child)))))))))

;; ----- Sync from an external library

(t/deftest ^:async sync-from-an-external-library
  (await
   (mock/with-mocks*
     {rp/cmd!   rpc-on-subscribe
      rx/timer  mock/timer-mock}
     (let [library (install-with-library! true)]
       (await (ftm/await-step [(dwl/set-updating-library true)
                               (dwl/sync-file (:id (ftm/current-file)) (:id library))]))
       (t/is (= new-color (copy-fill)))
       (t/is (not (:updating-library @st/state)))
       (t/is (some? (:synced-at (live-library))))
       (t/is (not= synced-at (:synced-at (live-library))))
       (t/is (= :settled (await (await-sync-status-sent))))))))

(t/deftest ^:async set-updating-library
  (await (ftm/await-step [(dwl/set-updating-library true)]))
  (t/is (true? (:updating-library @st/state)))
  (await (ftm/await-step [(dwl/set-updating-library false)]))
  (t/is (nil? (:updating-library @st/state))))
