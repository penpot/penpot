;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.persistence-context-loss-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- queued-state
  [{:keys [read-only? preview-id]}]
  (let [file-id   (uuid/next)
        commit-id (uuid/next)
        commit    {:id commit-id
                   :file-id file-id
                   :file-revn 1
                   :file-vern 0
                   :changes []
                   :features #{}
                   :origin :test
                   :created-at 0}]
    {:session-id (uuid/next)
     :permissions {:can-edit true}
     :workspace-global (cond-> {:read-only? read-only?}
                         preview-id (assoc :preview-id preview-id))
     :persistence {:queue #queue [commit-id]
                   :index {commit-id commit}}}))

(defn- watch-persist-commit
  [state]
  (let [commit-id (peek (get-in state [:persistence :queue]))]
    (ptk/watch (#'dps/persist-commit commit-id) state nil)))

(t/deftest persist-commit-ignores-workspace-read-only
  (t/async done
    (let [state (queued-state {:read-only? true})]
      (mock/with-mocks
        {rp/cmd! (mock/stub
                  (fn [cmd _params]
                    (swap! mock/rpc-calls conj {:cmd cmd})
                    (rx/of {:revn 2 :lagged []})))}
        (fn [done']
          (let [obs (watch-persist-commit state)]
            (t/is (some? obs))
            (->> obs
                 (rx/take 1)
                 (rx/subs!
                  (fn [_]
                    (t/is (= :update-file (-> @mock/rpc-calls first :cmd)))
                    (done'))
                  (fn [cause]
                    (t/is false (str cause))
                    (done'))))))
        done))))

(t/deftest persist-commit-skips-update-file-in-version-preview
  (t/async done
    (let [state (queued-state {:read-only? true
                               :preview-id (uuid/next)})]
      (mock/with-mocks
        {rp/cmd! mock/rpc-cmd-mock}
        (fn [done']
          (let [obs (watch-persist-commit state)]
            (t/is (nil? obs))
            (t/is (empty? @mock/rpc-calls))
            (done')))
        done))))

(t/deftest commit-changes-skips-while-version-preview
  (t/async done
    (let [state {:permissions {:can-edit true}
                 :workspace-global {:preview-id (uuid/next)}
                 :current-file-id (uuid/next)
                 :features #{}}
          event (dch/commit-changes {:redo-changes []
                                     :undo-changes []
                                     :save-undo? false})
          obs (ptk/watch event state nil)]
      (t/is (nil? obs))
      (done))))
