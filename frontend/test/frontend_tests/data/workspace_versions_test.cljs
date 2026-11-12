;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-versions-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.versions :as versions]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.ui.workspace.sidebar.versions :as versions-ui]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(t/deftest version-creation-rejects-save-failure-and-timeout
  (doseq [failure [:error :timeout]
          operation [:create :plugin-create]]
    (t/testing (str operation " after " failure)
      (let [file-id  (uuid/next)
            resolved (atom [])
            rejected (atom [])
            emitted  (atom [])
            requests (atom [])
            state    {:current-file-id file-id}
            event    (case operation
                       :create (versions/create-version)
                       :plugin-create (versions/create-version-from-plugins
                                       file-id "Version"
                                       #(swap! resolved conj %) #(swap! rejected conj %)))]
        (with-redefs [refs/persistence (atom {:status (if (= failure :error) :error :saving)
                                              :error {:type :persistence :code :save-permission-denied}})
                      rp/cmd! (mock/stub (fn [cmd _]
                                           (swap! requests conj cmd)
                                           (rx/of nil)))
                      rx/timeout (mock/stub (fn [_ fallback source]
                                              (if (= failure :timeout) fallback source)))]
          (->> (ptk/watch event state (rx/empty))
               (rx/subs! #(swap! emitted conj %) #(swap! rejected conj %)))
          (t/is (empty? @requests) "Do not create a version without saving")
          (t/is (empty? @resolved) "Do not resolve the plugin promise on failure")
          (if (= operation :create)
            ;; The save failure is reported on its own; the user is told
            ;; why the version is missing instead
            (let [notice (some #(when (= ::ntf/show (ptk/type %))
                                  (:notification (ptk/update % {})))
                               @emitted)]
              (t/is (empty? @rejected))
              (t/is (= "workspace.versions.create-failed-unsaved" (:content notice)))
              (t/is (= :warning (:level notice))))
            (do
              (t/is (= 1 (count @rejected)))
              (t/is (= (if (= failure :error) :save-permission-denied :save-timeout)
                       (:code (ex-data (first @rejected))))))))))))

(t/deftest repair-snapshots-are-told-apart-from-autosaves
  (t/is (versions/repair-snapshot? {:label "internal/repair/5" :created-by "system"}))
  (t/is (not (versions/repair-snapshot? {:label "internal/snapshot/20" :created-by "system"})))
  (t/is (not (versions/repair-snapshot? {:label "My version" :created-by "user"})))
  (t/is (not (versions/repair-snapshot? {:created-by "user"}))))

(defn- stored-days
  [flags team profile]
  (with-redefs [cf/flags flags]
    (#'versions-ui/get-versions-stored-days team profile)))

(def ^:private admin-console-flags
  (conj cf/flags :admin-console))

(def ^:private no-admin-console-flags
  (disj cf/flags :admin-console))

(t/deftest an-active-nitrate-license-keeps-ninety-days
  (t/is (= 90 (stored-days admin-console-flags
                           {:subscription {:type "unlimited" :status "active"}}
                           {:subscription {:type "nitrate" :status "active"}}))))

(t/deftest an-active-enterprise-license-keeps-ninety-days
  (t/is (= 90 (stored-days admin-console-flags
                           {:subscription {:type "unlimited" :status "active"}}
                           {:subscription {:type "enterprise" :status "active"}}))))

(t/deftest a-canceled-license-leaves-the-team-plan-in-charge
  (t/is (= 30 (stored-days admin-console-flags
                           {:subscription {:type "unlimited" :status "active"}}
                           {:subscription {:type "nitrate" :status "canceled"}}))))

(t/deftest a-license-without-the-admin-console-does-not-count
  (t/is (= 30 (stored-days no-admin-console-flags
                           {:subscription {:type "unlimited" :status "active"}}
                           {:subscription {:type "nitrate" :status "active"}}))))

(t/deftest an-unlimited-team-keeps-thirty-days
  (t/is (= 30 (stored-days no-admin-console-flags
                           {:subscription {:type "unlimited" :status "active"}}
                           {:subscription {:type "professional" :status "active"}}))))

(t/deftest an-enterprise-team-keeps-ninety-days
  (t/is (= 90 (stored-days no-admin-console-flags
                           {:subscription {:type "enterprise" :status "active"}}
                           {:subscription {:type "professional" :status "active"}}))))

(t/deftest a-professional-team-keeps-seven-days
  (t/is (= 7 (stored-days no-admin-console-flags
                          {:subscription {:type "professional" :status "active"}}
                          {:subscription {:type "professional" :status "active"}}))))

(t/deftest a-team-without-a-plan-keeps-seven-days
  (t/is (= 7 (stored-days no-admin-console-flags
                          {:subscription {:type nil :status "active"}}
                          {:subscription {:type "professional" :status "active"}}))))

;; Scenario: on one day the backend kept two autosaves and the copy from
;; before a repair. Proves: the history shows the repair copy as its own
;; entry, above the day's autosaves, so it can be found by its label.
(t/deftest the-history-lists-the-copy-before-a-repair-apart
  (let [at      (fn [hh] (ct/inst (str "2026-11-03T" hh ":00:00Z")))
        data    [{:id (uuid/next) :created-by "system" :label "internal/snapshot/0" :created-at (at "10")}
                 {:id (uuid/next) :created-by "system" :label "internal/repair/5" :created-at (at "11")}
                 {:id (uuid/next) :created-by "system" :label "internal/snapshot/20" :created-at (at "12")}]
        entries (#'versions-ui/group-snapshots data)]
    (t/is (= [:repair :autosave] (mapv :kind entries)))
    (t/is (= [["internal/repair/5"] ["internal/snapshot/0" "internal/snapshot/20"]]
             (mapv #(mapv :label (:snapshots %)) entries)))))

(t/deftest leaving-the-workspace-clears-the-preview-flags
  (let [team-id  (uuid/next)
        file-id  (uuid/next)
        other-id (uuid/next)
        output   (atom [])
        state    (-> {:current-file-id file-id
                      :current-team-id team-id
                      :permissions {:can-edit true}
                      :files {file-id {:id file-id :revn 0 :vern 0}}
                      :workspace-global {:read-only? true :preview-id (uuid/next)}}
                     (as-> $ (ptk/update (dw/finalize-workspace team-id file-id) $))
                     (assoc :current-file-id other-id)
                     (assoc-in [:files other-id] {:id other-id :revn 0 :vern 0}))]
    (t/is (nil? (get-in state [:workspace-global :preview-id])))
    (when-let [result (ptk/watch (dch/commit-changes {:redo-changes [] :undo-changes []})
                                 state (rx/empty))]
      (->> result (rx/subs! #(swap! output conj %))))
    (t/is (= 1 (count @output))
          "Editing must work again on the next file opened in the tab")))

(t/deftest strict-persistence-wait-rejects-a-real-timeout
  (t/async done
    (mock/with-mocks
      {refs/persistence (atom {:status :saving})}
      (fn [finish]
        (->> (dps/wait-persisted-or-error 0)
             (rx/subs! (fn [_]
                         (t/is false "The stalled save must not succeed")
                         (finish))
                       (fn [cause]
                         (t/is (= :save-timeout (:code (ex-data cause))))
                         (finish)))))
      done)))

(t/deftest strict-persistence-wait-observes-a-later-failure
  (let [pstate   (atom {:status :saving})
        rejected (atom [])
        resolved (atom [])]
    (with-redefs [refs/persistence pstate]
      (let [subscription (->> (dps/wait-persisted-or-error)
                              (rx/subs! #(swap! resolved conj %) #(swap! rejected conj %)))]
        (try
          (reset! pstate {:status :error :error {:code :missing-commit}})
          (t/is (empty? @resolved))
          (t/is (= :missing-commit (:code (ex-data (first @rejected)))))
          (finally
            (rx/dispose! subscription)))))))

(t/deftest failed-restore-returns-to-the-live-file-before-reporting
  ;; The plugin promise is rejected on a later tick, so every assertion about
  ;; the rejection runs once the event loop has drained.
  (t/async done
    (let [deferred (atom [])]
      (doseq [operation [:ui :plugin]
              failure [:saving :timeout :restore]
              preview? [true false]]
        (let [label      (str operation " after " failure ", preview=" preview?)
              file-id    (uuid/next)
              version-id (uuid/next)
              backup     {:id file-id :revn 10}
              snapshot   {:id file-id :revn 5}
              rejected   (atom [])
              resolved   (atom [])
              requests   (atom [])
              initialized (atom [])
              at-reject  (atom nil)
              store      (ptk/store {:state (cond-> {:current-file-id file-id
                                                     :files {file-id backup}}
                                              preview?
                                              (assoc :files {file-id snapshot}
                                                     :workspace-global {:read-only? true :preview-id version-id}
                                                     :workspace-versions {:backup backup}))
                                     :on-error #(swap! rejected conj %)})
              reject     (fn [cause]
                           (reset! at-reject
                                   {:file (get-in @store [:files file-id])
                                    :preview-id (get-in @store [:workspace-global :preview-id])})
                           (swap! rejected conj cause))
              event      (if (= operation :ui)
                           (#'versions/restore-version version-id)
                           (versions/restore-version-from-plugin
                            file-id version-id #(swap! resolved conj %) reject))]
          (t/testing label
            (with-redefs [refs/persistence (atom {:status (case failure
                                                            :saving :error :timeout :saving :saved)})
                          rp/cmd! (mock/stub (fn [cmd _]
                                               (swap! requests conj cmd)
                                               (rx/throw (ex-info "Restore failed" {:type :internal}))))
                          rx/timeout (mock/stub (fn [_ fallback source]
                                                  (if (= failure :timeout) fallback source)))
                          dwpg/initialize-page (mock/stub
                                                (fn [_ _]
                                                  (fn [state]
                                                    (swap! initialized conj (get-in state [:files file-id]))
                                                    state)))]
              (ptk/emit! store event)
              (t/is (empty? @resolved))
              (t/is (= (if (= failure :restore) [:restore-file-snapshot] []) @requests)
                    "Do not restore a version without saving")
              (t/is (= backup (get-in @store [:files file-id])))
              (t/is (nil? (get-in @store [:workspace-global :read-only?])))
              (t/is (nil? (get-in @store [:workspace-global :preview-id])))
              (t/is (nil? (get-in @store [:workspace-versions :backup])))
              (t/is (= (if preview? [backup] []) @initialized))))
          (swap! deferred conj
                 (fn []
                   (t/testing label
                     (t/is (= 1 (count @rejected)))
                     (when (= operation :plugin)
                       (t/is (= {:file backup :preview-id nil} @at-reject)
                             "The live file is back before the plugin promise rejects")))
                   (rx/dispose! store)))))
      (tm/schedule 50 (fn []
                        (doseq [check @deferred] (check))
                        (done))))))

(t/deftest successful-restore-clears-preview-only-after-loading
  (let [file-id (uuid/next)
        state   {:current-file-id file-id
                 :current-team-id (uuid/next)
                 :current-page-id (uuid/next)
                 :workspace-global {:read-only? true :preview-id (uuid/next)}
                 :workspace-versions {:backup {:id file-id}}}
        stream  (rx/subject)
        events  (atom [])
        cleanup #(filter (ptk/type? ::versions/clear-preview-state) @events)
        sub     (->> (ptk/watch (#'versions/initialize-version) state stream)
                     (rx/subs! #(swap! events conj %)))]
    (try
      (t/is (empty? (cleanup)))
      (rx/push! stream (ptk/data-event ::dw/bundle-fetched nil))
      (t/is (= 1 (count (cleanup))))
      (let [result (ptk/update (first (cleanup)) state)]
        (t/is (nil? (get-in result [:workspace-global :read-only?])))
        (t/is (nil? (get-in result [:workspace-global :preview-id])))
        (t/is (nil? (get-in result [:workspace-versions :backup]))))
      (finally
        (rx/dispose! sub)))))
