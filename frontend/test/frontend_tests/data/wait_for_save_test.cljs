;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.wait-for-save-test
  "Contract tests for app.plugins.persist/wait-for-save (plugin waitForSave)."
  (:require
   [app.main.data.persistence :as dps]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.plugins.persist :as persist]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp :refer [local-commit with-persistence]]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(defn- with-save-mocks
  "Redirects persistence refs and st/emit! onto the fixture store."
  [store body]
  (mock/with-mocks*
    {refs/persistence (l/derived :persistence store)
     st/state         store
     st/stream        (ptk/input-stream store)}
    (body)))

(defn- await-outcome
  "Resolves to {:ok value} or {:error err} for a JS promise."
  [p]
  (-> p
      (.then (fn [v] {:ok v}))
      (.catch (fn [e] {:error e}))))

(t/deftest ^:async empty-persistence-resolves-after-intake
  (await
   (with-persistence
     (^:async fn [{:keys [store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (let [outcome (await (await-outcome (persist/wait-for-save 2000)))]
              (t/is (contains? outcome :ok)
                    "empty buffer and queue resolve after intake")))))))))

(t/deftest ^:async invalid-timeout-rejects
  (await
   (with-persistence
     (^:async fn [{:keys [store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (let [outcome (await (await-outcome (persist/wait-for-save -1)))]
              (t/is (contains? outcome :error))
              (t/is (re-find #"not valid|waitForSave"
                             (or (some-> (:error outcome) .-message) "")))))))))))

(t/deftest ^:async terminal-error-rejects
  (await
   (with-persistence
     (^:async fn [{:keys [store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            ;; Empty queue+buffer with :error is a terminal waiter release
            ;; (see dps/terminal-status?).
            (swap! store assoc-in [:persistence :status] :error)
            (swap! store assoc-in [:persistence :error]
                   {:type :persistence :code :save-failed})
            (swap! store assoc-in [:persistence :queue] #queue [])
            (swap! store assoc-in [:persistence :buffer] [])
            (let [outcome (await (await-outcome (persist/wait-for-save 2000)))]
              (t/is (contains? outcome :error)
                    "terminal :error rejects waitForSave")
              (t/is (re-find #"(?i)could not be saved|save"
                             (or (some-> (:error outcome) .-message) "")))))))))))

(t/deftest ^:async rolled-back-during-wait-rejects
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (ptk/emit! store (local-commit file-id))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "commit is buffered"))
            (let [p (persist/wait-for-save 5000)
                  _ (js/setTimeout
                     (fn []
                       (ptk/emit! store
                                  (ptk/data-event ::dps/rolled-back
                                                  {:file-ids #{file-id}
                                                   :page-ids #{}
                                                   :shape-ids #{}})))
                     20)
                  outcome (await (await-outcome p))]
              (t/is (contains? outcome :error)
                    "rollback during wait rejects even if status later looks saved")
              (t/is (re-find #"(?i)roll"
                             (or (some-> (:error outcome) .-message) "")))
              ;; Keep the save path from hanging the fixture
              (when (seq (get-in @store [:persistence :queue]))
                (rx/push! response {:revn 1}))))))))))

(t/deftest ^:async buffer-race-waits-for-commit-not-stale-saved
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (t/is (empty? (get-in @store [:persistence :buffer])))
            (t/is (or (nil? (get-in @store [:persistence :status]))
                      (= :saved (get-in @store [:persistence :status]))))
            ;; Emit after starting the wait so a naive immediate empty
            ;; :saved resolve would succeed before the commit buffers.
            (let [p (persist/wait-for-save 5000)]
              (js/setTimeout
               (fn [] (ptk/emit! store (local-commit file-id)))
               0)
              (await (async/wait-for #(seq @requests) "force-persist sent the edit"))
              (rx/push! response {:revn 1})
              (let [outcome (await (await-outcome p))]
                (t/is (contains? outcome :ok)
                      "wait covers the late-buffered commit instead of stale empty :saved"))))))))))

(t/deftest ^:async pending-work-emits-force-persist
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (ptk/emit! store (local-commit file-id))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "commit is buffered"))
            (let [p (persist/wait-for-save 5000)]
              (await (async/wait-for #(seq @requests) "force-persist flushed the buffer"))
              (rx/push! response {:revn 1})
              (let [outcome (await (await-outcome p))]
                (t/is (contains? outcome :ok)
                      "pending work resolves once the save lands"))))))))))

(t/deftest ^:async pending-work-short-timeout-rejects
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (ptk/emit! store (local-commit file-id))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "commit is buffered"))
            ;; Never answer the save; the wait must time out.
            (let [outcome (await (await-outcome (persist/wait-for-save 30)))]
              (t/is (contains? outcome :error)
                    "pending work with no save answer rejects on timeout")
              (t/is (re-find #"(?i)timed out|waiting"
                             (or (some-> (:error outcome) .-message) "")))))))))))

(t/deftest ^:async empty-short-timeout-resolves
  (await
   (with-persistence
     (^:async fn [{:keys [store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            ;; Timeout shorter than the default 100ms intake; must still
            ;; resolve (capped intake), not reject as a timeout.
            (let [outcome (await (await-outcome (persist/wait-for-save 5)))]
              (t/is (contains? outcome :ok)
                    "empty short timeout resolves (nothing to save)")))))))))

(t/deftest ^:async soft-rejection-during-wait-rejects
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (let [commit (local-commit file-id)]
              (ptk/emit! store commit)
              (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                     "commit is buffered"))
              (let [p (persist/wait-for-save 5000)]
                (await (async/wait-for #(seq @requests)
                                       "force-persist flushed the buffer"))
                (rx/push! response (thp/rejection (:id @commit)))
                (let [outcome (await (await-outcome p))]
                  (t/is (contains? outcome :error)
                        "soft rejection rejects wait-for-save, not success on later :saved")
                  (t/is (re-find #"(?i)roll"
                                 (or (some-> (:error outcome) .-message) "")))))))))))))

(t/deftest ^:async rollback-rejected-beats-empty-saved
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (with-save-mocks store
          (^:async fn []
            (ptk/emit! store (local-commit file-id))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "commit is buffered"))
            (let [p (persist/wait-for-save 5000)]
              (await (async/wait-for #(seq @requests)
                                     "force-persist flushed the buffer"))
              ;; Rollback starts before settle can publish empty :saved;
              ;; never emit ::rolled-back (reload-delay case).
              (ptk/emit! store (ptk/data-event ::dps/rollback-rejected {}))
              (swap! store assoc-in [:persistence :status] :saved)
              (swap! store assoc-in [:persistence :buffer] [])
              (swap! store assoc-in [:persistence :queue] #queue [])
              (let [outcome (await (await-outcome p))]
                (t/is (contains? outcome :error)
                      "rollback-rejected rejects even when :saved would win the race")
                (t/is (re-find #"(?i)roll"
                               (or (some-> (:error outcome) .-message) "")))
                (when (seq (get-in @store [:persistence :queue]))
                  (rx/push! response {:revn 1})))))))))))
