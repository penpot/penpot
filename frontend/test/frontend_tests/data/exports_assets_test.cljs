;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-assets-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.exports.assets :as de]
   [app.main.data.exports.wasm :as wasm.exports]
   [app.main.data.persistence :as dwp]
   [app.main.errors :as errors]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.webapi :as wapi]
   [app.util.websocket :as ws]
   [app.worker.exports :as worker.exports]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.events :as the]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))
(def ^:private export {:id (uuid/next)
                       :object-id (uuid/next)
                       :type :png
                       :suffix ""
                       :scale 1})

(defn- export-with-name
  [name]
  (merge export {:name name}))

(defn- test-state
  []
  {:profile-id (:id export)
   :ws-conn nil})

(defn- wasm-state
  []
  (assoc (test-state) :features #{"render-wasm/v1"}))

(t/deftest sanitize-filename-strips-reserved-chars-and-dedupes
  (t/is (= "a_b.png"
           (wasm.exports/sanitize-filename {:name "a/b" :suffix "" :type :png} #{})))
  (t/is (= "board-2.pdf"
           (wasm.exports/sanitize-filename {:name "board" :suffix "" :type :pdf}
                                           #{"board.pdf"}))))

(t/deftest normalize-export-preserves-existing-name
  (t/is (= (export-with-name "Layer 1")
           (de/normalize-export (export-with-name "Layer 1")))))

(t/deftest normalize-export-replaces-nil-name-with-object-id
  (t/is (= (export-with-name (str (:object-id export)))
           (de/normalize-export (assoc export :name nil)))))

(t/deftest normalize-export-replaces-empty-name-with-object-id
  (t/is (= (export-with-name (str (:object-id export)))
           (de/normalize-export (assoc export :name "")))))

(t/deftest request-simple-export-sends-normalized-export
  (t/async done
    (let [export (export-with-name "")
          observed (atom nil)]
      (mock/with-mocks {repo/cmd! (mock/stub (fn [_ params]
                                               (reset! observed params)
                                               (rx/of {:filename "export.png"
                                                       :mtype "image/png"
                                                       :uri "blob:export"})))
                        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/of ::force-persisted)))
                        dom/trigger-download-uri (mock/stub (fn [& _] nil))}
        (fn [done']
          (let [completed (fn [_state]
                            (t/is (= (export-with-name (str (:object-id export)))
                                     (-> @observed :exports first))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-simple-export {:export export})
                       :the/end)))
        done))))

(t/deftest request-multiple-export-sends-normalized-enabled-exports
  (t/async done
    (let [exports [{:id "enabled-1"
                    :object-id "enabled-1"
                    :shape {:id "enabled-1"}
                    :type :png
                    :suffix ""
                    :scale 1
                    :enabled true
                    :name ""}]
          observed (atom nil)]
      (mock/with-mocks {repo/cmd! (mock/stub (fn [_ params]
                                               (reset! observed params)
                                               (rx/of {:id (:id export)})))
                        ws/get-rcv-stream (mock/stub (fn [_] (rx/empty)))
                        st/ongoing-tasks (atom #{})}
        (fn [done']
          (let [completed (fn [_state]
                            (t/is (= [{:id "enabled-1"
                                       :object-id "enabled-1"
                                       :shape {:id "enabled-1"}
                                       :type :png
                                       :suffix ""
                                       :scale 1
                                       :enabled true
                                       :name "enabled-1"}]
                                     (:exports @observed))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-multiple-export {:exports exports})
                       :the/end)))
        done))))


(defn- selected-shape-state
  [presets]
  {:current-file-id #uuid "00000000-0000-0000-0000-000000000001"
   :current-page-id #uuid "00000000-0000-0000-0000-000000000002"
   :workspace-local {:selected [(:object-id export)]}
   :files {#uuid "00000000-0000-0000-0000-000000000001"
           {:data {:pages-index
                   {#uuid "00000000-0000-0000-0000-000000000002"
                    {:objects {(:object-id export)
                               {:id (:object-id export)
                                :name "Asset"
                                :exports presets}}}}}}}})

(defn- ^:async selected-shape-events
  [state]
  (let [events (atom [])]
    (await (async/observe (ptk/watch (de/export-selected-shape) state nil)
                          :on-next #(swap! events conj %)))
    @events))

(t/deftest ^:async export-selected-shape-preserves-presets-and-routes-export
  (let [presets (mapv (fn [type] {:type type :scale 2 :suffix (str "-" (name type))})
                      [:png :jpeg :webp :svg :pdf])]
    (doseq [presets (conj (mapv vector presets) presets)]
      (let [state    (selected-shape-state presets)
            single?  (= 1 (count presets))
            name     (if single? (str "Asset" (:suffix (first presets))) "Asset")
            expected (mapv #(assoc % :name name
                                   :file-id (:current-file-id state)
                                   :page-id (:current-page-id state)
                                   :object-id (:object-id export)) presets)]
        (await
         (mock/with-mocks*
           {de/request-simple-export (fn [params] (ptk/data-event ::simple params))
            de/request-multiple-export (fn [params] (ptk/data-event ::multiple params))}
           (let [[request analytics :as events] (await (selected-shape-events state))]
             (t/is (= [(if single? ::simple ::multiple) ::ev/event] (mapv ptk/type events)))
             (t/is (= expected (:exports @request)))
             (when single? (t/is (= (first expected) (:export @request))))
             (t/is (= "workspace:shortcuts" (::ev/origin @analytics)))
             (t/is (= (count presets) (:num-shapes @analytics))))))))))

(t/deftest ^:async export-selected-shape-ignores-invalid-selection-or-context
  (let [state (selected-shape-state [{:type :png :scale 1 :suffix ""}])]
    (doseq [[reason state]
            [["no selection" (assoc-in state [:workspace-local :selected] [])]
             ["multiple selection" (assoc-in state [:workspace-local :selected]
                                             [(:object-id export) (uuid/next)])]
             ["missing shape" (assoc-in state [:workspace-local :selected] [(uuid/next)])]
             ["no presets" (selected-shape-state nil)]
             ["empty presets" (selected-shape-state [])]
             ["missing file" (dissoc state :current-file-id)]
             ["missing page" (dissoc state :current-page-id)]]]
      (t/is (empty? (await (selected-shape-events state))) reason))))


(t/deftest ^:async export-selected-shape-ignores-export-in-progress
  (doseq [presets [[{:type :png :scale 1 :suffix ""}]
                   [{:type :png :scale 1 :suffix ""}
                    {:type :svg :scale 1 :suffix "-vector"}]]]
    (let [state (assoc (selected-shape-state presets) :export {:in-progress true})]
      (t/is (empty? (await (selected-shape-events state))))
      (t/is (= 2 (count (await (selected-shape-events
                                (assoc-in state [:export :in-progress] false)))))))))

(t/deftest ^:async export-selected-shape-wasm-adds-suffix-once
  (doseq [type [:png :jpeg :webp :svg :pdf]]
    (let [preset {:type type :scale 2 :suffix "-final"}
          state (assoc (selected-shape-state [preset]) :features #{"render-wasm/v1"})
          downloaded (atom nil)
          requested (atom nil)
          store (ptk/store {:state state})]
      (await
       (mock/with-mocks*
         {dwp/force-persist-files-and-wait (mock/stub (fn [& _] (rx/empty)))
          wasm.api/export-target (mock/stub (constantly :client))
          st/ongoing-tasks (atom #{})
          mw/ask-many! (mock/stub
                        (fn [params]
                          (reset! requested params)
                          (rx/of {:type :done
                                  :filename (worker.exports/local-export-filename
                                             (:kind params) (first (:exports params)) (:name params))
                                  :blob (js/Blob.)})))
          wapi/create-uri (mock/stub (constantly "blob:export"))
          dom/trigger-download-uri (mock/stub (fn [filename & _] (reset! downloaded filename)))}
         (ptk/emit! store (de/export-selected-shape))
         (await (async/wait-for #(some? @downloaded) "worker download"))
         (t/is (= (str "Asset-final" (wasm.exports/export-extension type)) @downloaded))
         (t/is (= :single (:kind @requested)))
         (t/is (= {:object-id (:object-id export) :type type :scale 2
                   :name "Asset-final" :suffix "-final"}
                  (first (:exports @requested))))))
      (rx/dispose! store))))

(t/deftest ^:async export-selected-shape-blocks-pending-multiple-preset-requests
  (doseq [terminal ["ended" "error"]]
    (let [presets [{:type :png :scale 2 :suffix "-raster"}
                   {:type :svg :scale 1 :suffix "-vector"}]
          state (assoc (selected-shape-state presets)
                       :features #{})
          requests (atom [])
          analytics (atom [])
          errors (atom [])
          response (rx/subject)
          messages (rx/subject)
          resource-id (uuid/next)]
      (await
       (mock/with-mocks*
         {repo/cmd!
          (mock/stub (fn [command params]
                       (swap! requests conj [command params])
                       (rx/take 1 (rx/observe-on :async response))))
          ws/get-rcv-stream (fn [_conn] (rx/observe-on :async messages))
          st/ongoing-tasks (atom #{})
          dom/trigger-download-uri (mock/stub (fn [& _]))}
         (let [store (ptk/store {:state state :on-error #(swap! errors conj %)})
               subscription (->> (ptk/input-stream store)
                                 (rx/filter #(= ::ev/event (ptk/type %)))
                                 (rx/subs! #(swap! analytics conj %)))]
           (try
             (ptk/emit! store (de/export-selected-shape))
             (await (async/wait-for #(seq @requests) "initial export request"))
             (t/is (true? (get-in @store [:export :in-progress])))
             (ptk/emit! store (de/export-selected-shape) (de/export-selected-shape))
             (await (async/settle))
             (t/is (= 1 (count @requests)) "no duplicates before RPC response")
             (t/is (= 1 (count @analytics)) "no duplicate analytics")
             (t/is (= :export (ffirst @requests)))
             (t/is (= presets (mapv #(select-keys % [:type :scale :suffix])
                                    (get-in @requests [0 1 :exports]))))
             (rx/push! response {:id resource-id})
             (await (async/wait-for #(= resource-id (get-in @store [:export :resource-id]))
                                    "export initialized"))
             (ptk/emit! store (de/export-selected-shape))
             (await (async/settle))
             (t/is (= 1 (count @requests)) "no duplicates while running")
             (rx/push! messages {:type :message
                                 :payload {:type :export-update :resource-id resource-id
                                           :status terminal :done 2 :total 2
                                           :filename "assets.zip" :mtype "application/zip"
                                           :resource-uri "blob:assets"}})
             (await (async/wait-for #(false? (get-in @store [:export :in-progress]))
                                    "terminal export status"))
             (ptk/emit! store (de/export-selected-shape))
             (await (async/wait-for #(= 2 (count @requests)) "retry after terminal status"))
             (ptk/emit! store (de/clear-export-state resource-id) (de/export-selected-shape))
             (await (async/settle))
             (t/is (and (true? (get-in @store [:export :in-progress]))
                        (= 2 (count @requests))) "old cleanup cannot release pending retry")
             (let [retry-id (uuid/next)]
               (rx/push! response {:id retry-id})
               (await (async/wait-for #(= retry-id (get-in @store [:export :resource-id]))
                                      "retry initialized"))
               (ptk/emit! store (de/clear-export-state resource-id) (de/export-selected-shape))
               (await (async/settle))
               (t/is (and (true? (get-in @store [:export :in-progress]))
                          (= 2 (count @requests))) "old cleanup cannot release running retry"))
             (await (async/settle))
             (t/is (= 2 (count @analytics)))
             (t/is (empty? @errors))
             (finally
               (rx/end! response)
               (rx/end! messages)
               (await (js/Promise. (fn [resolve] (js/setTimeout resolve 650))))
               (rx/dispose! subscription)
               (rx/dispose! store)))))))))

(t/deftest ^:async export-entry-points-share-pending-request-ownership
  (doseq [first-multiple? [false true]
          shortcut-first? [false true]]
    (let [presets [{:type :png :scale 1 :suffix ""}
                   {:type :svg :scale 1 :suffix "-vector"}]
          state (assoc (selected-shape-state (if first-multiple? presets [(first presets)]))
                       :features #{})
          requests (atom [])
          response (rx/subject)
          messages (rx/subject)
          errors (atom [])]
      (await
       (mock/with-mocks*
         {repo/cmd! (mock/stub (fn [command params]
                                 (swap! requests conj [command params])
                                 (rx/take 1 (rx/observe-on :async response))))
          dwp/force-persist-files-and-wait (mock/stub (fn [& _] (rx/empty)))
          ws/get-rcv-stream (fn [_] (rx/observe-on :async messages))
          st/ongoing-tasks (atom #{})}
         (let [store (ptk/store {:state state :on-error #(swap! errors conj %)})]
           (try
             (ptk/emit! store (if shortcut-first?
                                (de/export-selected-shape)
                                (de/request-export {:exports (if first-multiple? presets [(first presets)])})))
             (await (async/wait-for #(= 1 (count @requests)) "first request"))
             (let [owner (:export @store)]
                 ;; The dialog and sidebar call these events directly.
               (ptk/emit! store
                          (de/request-multiple-export {:exports presets})
                          (de/request-simple-export {:export (first presets)})
                          (de/export-selected-shape))
               (await (async/settle))
               (t/is (= 1 (count @requests)) "all entry points respect the pending export")
               (t/is (= owner (:export @store)) "a refused request cannot replace the owner"))
             (t/is (empty? @errors))
             (finally
               (rx/end! response)
               (rx/end! messages)
               (await (js/Promise. (fn [resolve] (js/setTimeout resolve 650))))
               (rx/dispose! store)))))))))

(t/deftest ^:async old-export-events-cannot-change-a-following-export
  (do
    (let [presets [{:type :png :scale 1 :suffix ""}
                   {:type :svg :scale 1 :suffix "-vector"}]
          state (assoc (selected-shape-state presets)
                       :features #{})
          requests (atom [])
          downloads (atom [])
          errors (atom [])
          response (rx/subject)
          cancellation (rx/subject)
          messages (rx/subject)
          first-id (uuid/next)
          second-id (uuid/next)
          job (fn [id] {:id id})
          message (fn [id status]
                    {:type :message
                     :payload {:type :export-update :resource-id id
                               :status status :done 2 :total 2
                               :filename "assets.zip" :mtype "application/zip"
                               :resource-uri "blob:assets"}})]
      (await
       (mock/with-mocks*
         {repo/cmd! (mock/stub (fn [command params]
                                 (swap! requests conj [command params])
                                 (rx/take 1 (rx/observe-on :async
                                                           (if (= command :cancel-export-job)
                                                             cancellation response)))))
          ws/get-rcv-stream (fn [_] (rx/observe-on :async messages))
          st/ongoing-tasks (atom #{})
          dom/trigger-download-uri (mock/stub (fn [& args] (swap! downloads conj args) nil))}
         (let [store (ptk/store {:state state :on-error #(swap! errors conj %)})]
           (try
             (ptk/emit! store (de/export-selected-shape))
             (await (async/wait-for #(seq @requests) "first request"))
             (rx/push! response (job first-id))
             (await (async/wait-for #(= first-id (get-in @store [:export :id])) "first initialized"))
             (rx/push! messages (message first-id "ended"))
             (await (async/wait-for #(false? (get-in @store [:export :in-progress])) "first ended"))
             (ptk/emit! store (de/export-selected-shape))
             (await (async/wait-for #(= 2 (count @requests)) "second request"))
             (rx/push! response (job second-id))
             (await (async/wait-for #(= second-id (get-in @store [:export :id])) "second initialized"))
             (let [owner (:export @store)
                   downloaded (count @downloads)]
               (doseq [status ["running" "error" "cancelled" "ended"]]
                 (rx/push! messages (message first-id status)))
               (ptk/emit! store (de/clear-export-state first-id))
               (await (async/settle))
               (await (async/settle))
               (t/is (= owner (:export @store)) "old progress, error, completion, cancel and cleanup preserve the new owner")
               (t/is (= downloaded (count @downloads)) "old completion does not download again")
               (await (js/Promise. (fn [resolve] (js/setTimeout resolve 650))))
               (t/is (seq @st/ongoing-tasks) "old finalization does not remove the new ongoing task"))
             (rx/push! messages (message second-id "ended"))
             (await (async/wait-for #(false? (get-in @store [:export :in-progress])) "second ended"))
             (t/is (empty? @errors))
             (finally
               (rx/end! response)
               (rx/end! cancellation)
               (rx/end! messages)
               (await (js/Promise. (fn [resolve] (js/setTimeout resolve 650))))
               (rx/dispose! store)))))))))

(t/deftest ^:async single-export-cleanup-belongs-to-one-request
  (let [state (selected-shape-state [{:type :png :scale 1 :suffix ""}])
        requests (atom 0)
        cleanups (atom [])
        response (rx/subject)
        errors (atom [])]
    (await
     (mock/with-mocks*
       {repo/cmd! (mock/stub (fn [& _]
                               (swap! requests inc)
                               (rx/take 1 (rx/observe-on :async response))))
        dwp/force-persist-and-wait (mock/stub (fn [_] (rx/empty)))
        dom/trigger-download-uri (mock/stub (fn [& _]))}
       (let [store (ptk/store {:state state :on-error #(swap! errors conj %)})
             subscription (->> (ptk/input-stream store)
                               (rx/filter #(= ::de/clear-export-state (ptk/type %)))
                               (rx/subs! #(swap! cleanups conj %)))]
         (try
           (ptk/emit! store (de/export-selected-shape))
           (await (async/wait-for #(= 1 @requests) "first single export"))
           (rx/push! response {:filename "asset.png" :mtype "image/png" :uri "blob:asset"})
           (await (async/wait-for #(seq @cleanups) "first cleanup"))
           (t/is (nil? (:export @store)))
           (ptk/emit! store (de/export-selected-shape))
           (await (async/wait-for #(= 2 @requests) "next single export"))
           (let [owner (:export @store)]
             (ptk/emit! store (first @cleanups))
             (await (async/settle))
             (t/is (= owner (:export @store)) "an old simple-export cleanup cannot release a new request"))
           (rx/push! response {:filename "asset.png" :mtype "image/png" :uri "blob:asset"})
           (await (async/wait-for #(nil? (:export @store)) "second cleanup"))
           (t/is (empty? @errors))
           (finally
             (rx/end! response)
             (rx/dispose! subscription)
             (rx/dispose! store))))))))

(t/deftest old-cleanup-preserves-a-new-request-failure
  (let [old-id (uuid/next)
        request (de/request-multiple-export {:exports [export export]})
        pending (ptk/update request {})
        request-id (get-in pending [:export :id])
        failed (ptk/update (#'de/export-widget-failed request-id [export export]
                                                      :export-shapes (ex-info "Queue full" {:code :queue-full}))
                           pending)]
    (t/is (= :queue-full (get-in failed [:export :error-code])))
    (t/is (= failed (ptk/update (de/clear-export-state old-id) failed)))))

(t/deftest ^:async wasm-retry-ignores-old-worker-completion
  (doseq [presets [[{:type :png :scale 1 :suffix ""}]
                   [{:type :png :scale 1 :suffix ""} {:type :svg :scale 1 :suffix "-vector"}]]]
    (let [state (assoc (selected-shape-state presets) :features #{"render-wasm/v1"})
          streams (atom [])
          save-response (rx/subject)
          first-save? (atom true)
          flashed (atom [])
          downloads (atom [])
          failures (atom [])
          store (ptk/store {:state state :on-error #(swap! failures conj %)})]
      (await
       (mock/with-mocks*
         {dwp/force-persist-files-and-wait
          (mock/stub (fn [& _]
                       (if (compare-and-set! first-save? true false)
                         (rx/take 1 save-response)
                         (rx/empty))))
          wasm.api/export-target (mock/stub (constantly :client))
          mw/ask-many! (mock/stub (fn [_]
                                    (let [stream (rx/subject)]
                                      (swap! streams conj stream)
                                      (rx/observe-on :async stream))))
          st/ongoing-tasks (atom #{})
          errors/flash (fn [& args] (swap! flashed conj args))
          wapi/create-uri (mock/stub (constantly "blob:export"))
          dom/trigger-download-uri (mock/stub (fn [& args] (swap! downloads conj args)))}
         (try
           (ptk/emit! store (de/export-selected-shape))
           (await (async/settle))
           (let [owner (:export @store)]
             (t/is (true? (:in-progress owner)))
             (ptk/emit! store (de/export-selected-shape)
                        (de/request-simple-export {:export (first presets)})
                        (de/request-multiple-export {:exports presets}))
             (await (async/settle))
             (t/is (= owner (:export @store)) "all entry points respect the pending save")
             (t/is (empty? @streams)))
           (rx/push! save-response ::saved)
           (await (async/wait-for #(= 1 (count @streams)) "first worker"))
           (ptk/emit! store (de/export-selected-shape))
           (await (async/settle))
           (t/is (= 1 (count @streams)) "busy worker blocks repeat shortcut")
           (let [old-id (get-in @store [:export :id])]
             (rx/push! (first @streams) {:type :error :hint "render failed"})
             (await (async/wait-for #(false? (get-in @store [:export :in-progress])) "worker failure"))
             (t/is (= 1 (count @flashed)))
             (ptk/emit! store (de/export-selected-shape))
             (await (async/wait-for #(= 2 (count @streams)) "worker retry"))
             (let [owner (:export @store)]
               (ptk/emit! store (de/clear-export-state old-id))
               (rx/push! (first @streams) {:type :progress :done 1 :total (count presets)})
               (rx/end! (first @streams))
               (await (async/wait-for #(= 1 (count @st/ongoing-tasks)) "old worker finalized"))
               (t/is (= owner (:export @store)))
               (t/is (empty? @downloads))
               (t/is (= 1 (count @st/ongoing-tasks)) "old finalizer preserves retry task"))
             (rx/push! (second @streams) {:type :done :filename "retry.png" :blob (js/Blob.)})
             (await (async/wait-for #(= 1 (count @downloads)) "retry download"))
             (t/is (false? (get-in @store [:export :in-progress])))
             (t/is (empty? @failures)))
           (finally
             (rx/end! save-response)
             (doseq [stream @streams] (rx/end! stream))
             (rx/dispose! store))))))))

(defn- export-store
  "A store for the export tests that records store errors in `store-errors`."
  [state store-errors]
  (ptk/store {:state state :on-error #(swap! store-errors conj %)}))

(defn- unsaved-state
  "Test state whose save queue still holds a change of `file-id`."
  [file-id]
  (let [commit-id (uuid/next)]
    (assoc (test-state)
           :persistence {:queue (conj #queue [] commit-id)
                         :index {commit-id {:id commit-id :file-id file-id}}})))

;; Scenario: the exporter fails a single export. Proves: the user gets a
;; toast and stays in the workspace; the failure is not raised to the global
;; error handler, which would replace the workspace with an error page.
(t/deftest ^:async failed-simple-export-shows-a-toast-instead-of-raising
  (let [store-errors (atom [])
        flashed      (atom [])
        store        (export-store (test-state) store-errors)]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/throw (ex-info "exporter failed"
                                                                         {:type :server-error
                                                                          :code :unhandled}))))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        errors/flash                     (fn [& {:keys [hint type]}]
                                           (swap! flashed conj [hint type]))
        i18n/tr                          (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-simple-export {:export export}))
       (await (async/wait-for #(seq @flashed) "the failure is shown"))
       (await (async/settle))
       (t/is (= [["translated:errors.export-failed" :unhandled]] @flashed)
             "an exporter defect reports as unhandled")
       (t/is (empty? @store-errors) "nothing reaches the global error handler")
       (t/is (nil? (:export @store)) "the export state is cleared")))))

;; Scenario: the export request of a multiple export fails. Proves: the user
;; gets a toast, and the leave-page warning the export turned on is released
;; even though no progress message will ever arrive.
(t/deftest ^:async failed-multiple-export-shows-a-toast-and-releases-the-leave-warning
  (let [store-errors (atom [])
        flashed      (atom [])
        tasks        (atom #{})
        store        (export-store (test-state) store-errors)]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/throw (ex-info "exporter failed"
                                                                         {:type :server-error}))))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        ws/get-rcv-stream                (mock/stub (fn [_] (rx/subject)))
        st/ongoing-tasks                 tasks
        errors/flash                     (fn [& {:keys [hint]}] (swap! flashed conj hint))
        i18n/tr                          (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-multiple-export {:exports [(assoc export :enabled true)]}))
       (await (async/wait-for #(seq @flashed) "the failure is shown"))
       (await (async/wait-for #(not (contains? @tasks :export))
                              "the leave-page warning is released"))
       (t/is (= ["translated:errors.export-failed"] @flashed))
       (t/is (empty? @store-errors) "nothing reaches the global error handler")))))

;; Scenario: an export starts while its file has a change not saved yet.
;; Proves: the user is warned that the export may miss it, and the export
;; runs anyway.
(t/deftest ^:async export-warns-when-the-file-has-unsaved-changes
  (let [file-id    (uuid/next)
        downloaded (atom false)
        store      (export-store (unsaved-state file-id) (atom []))]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/of {:filename "export.png"
                                                              :mtype "image/png"
                                                              :uri "blob:export"})))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        dom/trigger-download-uri         (mock/stub (fn [& _] (reset! downloaded true)))
        i18n/tr                          (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-simple-export {:export (assoc export :file-id file-id)}))
       (await (async/wait-for #(true? @downloaded) "the export runs anyway"))
       (t/is (= "translated:workspace.export.unsaved-changes"
                (get-in @store [:notification :content])))
       (t/is (= :warning (get-in @store [:notification :level])))))))

;; Scenario: an export starts with every change of its file saved. Proves:
;; no warning is shown.
(t/deftest ^:async export-shows-no-warning-when-the-file-is-saved
  (let [downloaded (atom false)
        store      (export-store (test-state) (atom []))]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/of {:filename "export.png"
                                                              :mtype "image/png"
                                                              :uri "blob:export"})))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        dom/trigger-download-uri         (mock/stub (fn [& _] (reset! downloaded true)))}
       (ptk/emit! store (de/request-simple-export {:export (assoc export :file-id (uuid/next))}))
       (await (async/wait-for #(true? @downloaded) "the export runs"))
       (await (async/settle))
       (t/is (nil? (:notification @store)))))))

;; Scenario: WASM is on and the user exports one shape. Proves: the worker
;; builds a single blob (no ZIP); the exporter is not called.
;; Stub `export-target`: without a module it is nil; routing still stays
;; local (never the server API under wasm).
(t/deftest ^:async wasm-simple-export-stays-local
  (let [cmd-calls   (atom [])
        worker-kind (atom nil)
        downloaded  (atom nil)
        store       (export-store (wasm-state) (atom []))
        page-id     (uuid/next)
        file-id     (uuid/next)
        one         (assoc export :enabled true :name "layer"
                           :page-id page-id :file-id file-id)]
    (await
     (mock/with-mocks*
       {repo/cmd!                 (mock/stub (fn [cmd params]
                                               (swap! cmd-calls conj [cmd params])
                                               (rx/throw (ex-info "exporter must not run" {}))))
        wasm.api/export-target    (mock/stub (constantly :client))
        de/collect-export-objects (mock/stub (fn [_ _] {}))
        mw/ask-many!              (mock/stub (fn [msg]
                                               (reset! worker-kind (:kind msg))
                                               (rx/of {:type :done
                                                       :filename "layer.png"
                                                       :mtype "image/png"
                                                       :blob (js/Blob.)})))
        wapi/create-uri           (mock/stub (constantly "blob:png"))
        dom/trigger-download-uri  (mock/stub (fn [filename mtype uri]
                                               (reset! downloaded [filename mtype uri])))
        st/ongoing-tasks          (atom #{})}
       (ptk/emit! store (de/request-simple-export {:export one}))
       (await (async/wait-for #(some? @downloaded) "the file downloads"))
       (t/is (empty? @cmd-calls) "no exporter RPC")
       (t/is (= :single @worker-kind))
       (t/is (= ["layer.png" "image/png" "blob:png"] @downloaded))))))

;; Scenario: WASM says the raster is too big for the GPU (legacy `:exporter`
;; code). Proves: still local worker download, no server RPC — CPU raster
;; runs inside the worker via `export-bytes`.
(t/deftest ^:async wasm-oversized-for-gpu-stays-local
  (let [cmd-calls   (atom [])
        worker-kind (atom nil)
        downloaded  (atom nil)
        store       (export-store (wasm-state) (atom []))
        one         (assoc export :enabled true :name "huge")]
    (await
     (mock/with-mocks*
       {repo/cmd!                 (mock/stub (fn [cmd params]
                                               (swap! cmd-calls conj [cmd params])
                                               (rx/throw (ex-info "exporter must not run" {}))))
        wasm.api/export-target    (mock/stub (constantly :exporter))
        de/collect-export-objects (mock/stub (fn [_ _] {}))
        mw/ask-many!              (mock/stub (fn [msg]
                                               (reset! worker-kind (:kind msg))
                                               (rx/of {:type :done
                                                       :filename "huge.png"
                                                       :mtype "image/png"
                                                       :blob (js/Blob.)})))
        wapi/create-uri           (mock/stub (constantly "blob:png"))
        dom/trigger-download-uri  (mock/stub (fn [filename mtype uri]
                                               (reset! downloaded [filename mtype uri])))
        st/ongoing-tasks          (atom #{})}
       (ptk/emit! store (de/request-simple-export {:export one}))
       (await (async/wait-for #(some? @downloaded) "the file downloads"))
       (t/is (empty? @cmd-calls) "no exporter RPC")
       (t/is (= :single @worker-kind))
       (t/is (= ["huge.png" "image/png" "blob:png"] @downloaded))))))

;; Scenario: WASM reports true `:too-large` (beyond raster limits). Proves:
;; toast and no RPC — the exporter would fail the same way.
(t/deftest ^:async wasm-true-too-large-shows-toast
  (let [cmd-calls (atom [])
        store     (export-store (wasm-state) (atom []))
        one       (assoc export :enabled true :name "impossible")]
    (await
     (mock/with-mocks*
       {repo/cmd!              (mock/stub (fn [cmd params]
                                            (swap! cmd-calls conj [cmd params])
                                            (rx/throw (ex-info "exporter must not run" {}))))
        wasm.api/export-target (mock/stub (constantly :too-large))
        i18n/tr                (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-simple-export {:export one}))
       (await (async/wait-for-store store #(some? (:notification @store)) "the toast shows"))
       (t/is (empty? @cmd-calls) "no exporter RPC")
       (t/is (= "translated:errors.export-too-large"
                (get-in @store [:notification :content])))
       (t/is (= :error (get-in @store [:notification :level])))))))

;; Scenario: WASM is on and the user exports several shapes. Proves: nothing
;; hits the exporter; the worker builds a ZIP and the client downloads it.
(t/deftest ^:async wasm-multiple-export-stays-local
  (let [cmd-calls  (atom [])
        worker-cmd (atom nil)
        downloaded (atom nil)
        store      (export-store (wasm-state) (atom []))
        page-id    (uuid/next)
        file-id    (uuid/next)
        exports    [(assoc export :enabled true :name "a" :page-id page-id :file-id file-id)
                    (assoc export :id (uuid/next)
                           :object-id (uuid/next)
                           :page-id page-id
                           :file-id file-id
                           :enabled true
                           :name "b")]]
    (await
     (mock/with-mocks*
       {repo/cmd!                (mock/stub (fn [cmd params]
                                              (swap! cmd-calls conj [cmd params])
                                              (rx/throw (ex-info "exporter must not run" {}))))
        de/collect-export-objects (mock/stub (fn [_ _] {}))
        mw/ask-many!             (mock/stub (fn [msg]
                                              (reset! worker-cmd (:cmd msg))
                                              (rx/of {:type :done
                                                      :filename "page.zip"
                                                      :mtype "application/zip"
                                                      :blob (js/Blob.)})))
        wapi/create-uri          (mock/stub (constantly "blob:zip"))
        dom/trigger-download-uri (mock/stub (fn [filename mtype uri]
                                              (reset! downloaded [filename mtype uri])))
        st/ongoing-tasks         (atom #{})}
       (ptk/emit! store (de/request-multiple-export {:exports exports :name "page"}))
       (await (async/wait-for #(some? @downloaded) "the zip downloads"))
       (t/is (empty? @cmd-calls) "no exporter RPC")
       (t/is (= :exports/run-local @worker-cmd))
       (t/is (= ["page.zip" "application/zip" "blob:zip"] @downloaded))))))

;; Scenario: WASM is on and the user exports boards as PDF. Proves: worker
;; multi-page PDF, no exporter call.
(t/deftest ^:async wasm-export-frames-stays-local
  (let [cmd-calls  (atom [])
        worker-kind (atom nil)
        downloaded (atom nil)
        store      (export-store (wasm-state) (atom []))
        page-id    (uuid/next)
        file-id    (uuid/next)
        exports    [(assoc export :enabled true :name "board-1"
                           :page-id page-id :file-id file-id)
                    (assoc export :id (uuid/next)
                           :object-id (uuid/next)
                           :page-id page-id
                           :file-id file-id
                           :enabled true
                           :name "board-2")]]
    (await
     (mock/with-mocks*
       {repo/cmd!                (mock/stub (fn [cmd params]
                                              (swap! cmd-calls conj [cmd params])
                                              (rx/throw (ex-info "exporter must not run" {}))))
        de/collect-export-objects (mock/stub (fn [_ _] {}))
        mw/ask-many!             (mock/stub (fn [msg]
                                              (reset! worker-kind (:kind msg))
                                              (rx/of {:type :progress :done 1 :total 2}
                                                     {:type :progress :done 2 :total 2}
                                                     {:type :done
                                                      :filename "page.pdf"
                                                      :mtype "application/pdf"
                                                      :blob (js/Blob.)})))
        wapi/create-uri          (mock/stub (constantly "blob:pdf"))
        dom/trigger-download-uri (mock/stub (fn [filename mtype uri]
                                              (reset! downloaded [filename mtype uri])))
        st/ongoing-tasks         (atom #{})}
       (ptk/emit! store (de/request-multiple-export {:exports exports
                                                     :cmd :export-frames
                                                     :name "page"}))
       (await (async/wait-for #(some? @downloaded) "the pdf downloads"))
       (t/is (empty? @cmd-calls) "no exporter RPC")
       (t/is (= :pdf-frames @worker-kind))
       (t/is (= ["page.pdf" "application/pdf" "blob:pdf"] @downloaded))))))

;; Scenario: the user cancels a local WASM multi-export. Proves: the widget
;; stays cancelled and late worker :progress/:done do not download or flip
;; status back to running.
(t/deftest ^:async wasm-local-cancel-ignores-late-worker-messages
  (let [downloaded  (atom nil)
        cancel-cmds (atom [])
        worker-bus  (rx/subject)
        store       (export-store (wasm-state) (atom []))
        page-id     (uuid/next)
        file-id     (uuid/next)
        exports     [(assoc export :enabled true :name "a"
                            :page-id page-id :file-id file-id)
                     (assoc export :id (uuid/next)
                            :object-id (uuid/next)
                            :page-id page-id
                            :file-id file-id
                            :enabled true
                            :name "b")]]
    (await
     (mock/with-mocks*
       {repo/cmd!                 (mock/stub (fn [_ _]
                                               (rx/throw (ex-info "exporter must not run" {}))))
        de/collect-export-objects (mock/stub (fn [_ _] {}))
        mw/ask-many!              (mock/stub (fn [_] worker-bus))
        mw/emit!                  (mock/stub (fn [msg]
                                               (swap! cancel-cmds conj msg)))
        wapi/create-uri           (mock/stub (constantly "blob:zip"))
        dom/trigger-download-uri  (mock/stub (fn [filename mtype uri]
                                               (reset! downloaded [filename mtype uri])))
        st/ongoing-tasks          (atom #{})}
       (ptk/emit! store (de/request-multiple-export {:exports exports :name "page"}))
       (await (async/wait-for-store store #(= "running" (get-in @store [:export :status]))
                                    "export is running"))
       (rx/push! worker-bus {:type :progress :done 1 :total 2})
       (await (async/wait-for-store store #(= 1 (get-in @store [:export :progress]))
                                    "first progress lands"))
       (ptk/emit! store (de/cancel-export))
       (await (async/wait-for-store store #(= "cancelled" (get-in @store [:export :status]))
                                    "export is cancelled"))
       (rx/push! worker-bus {:type :progress :done 2 :total 2})
       (rx/push! worker-bus {:type :done
                             :filename "page.zip"
                             :mtype "application/zip"
                             :blob (js/Blob.)})
       (await (async/settle))
       (t/is (= "cancelled" (get-in @store [:export :status])))
       (t/is (nil? @downloaded) "late :done does not download")
       (t/is (= :exports/cancel (:cmd (first @cancel-cmds))))
       (rx/end! worker-bus)))))

(t/deftest collect-export-objects-keeps-only-roots-and-descendants
  (let [page-id (uuid/next)
        file-id (uuid/next)
        root    (uuid/next)
        child   (uuid/next)
        other   (uuid/next)
        objects {root  {:id root :parent-id uuid/zero :shapes [child]}
                 child {:id child :parent-id root :shapes []}
                 other {:id other :parent-id uuid/zero :shapes []}}
        state   {:files {file-id {:id file-id
                                  :data {:pages-index
                                         {page-id {:id page-id
                                                   :objects objects}}}}}}
        exports [{:object-id root :page-id page-id :file-id file-id}]
        selected (de/collect-export-objects state exports)]
    (t/is (= #{root child} (set (keys selected))))
    (t/is (not (contains? selected other)))))

;; Scenario: a shape is drawn in the browser while its file has a change not
;; saved yet. Proves: the export waits for the save first, and the user is
;; told the export includes changes the server does not have.
(t/deftest ^:async client-export-waits-for-the-save-and-warns-of-unsaved-changes
  (let [file-id (uuid/next)
        calls   (atom [])
        store   (export-store (assoc (unsaved-state file-id) :features #{"render-wasm/v1"})
                              (atom []))]
    (await
     (mock/with-mocks*
       {dwp/force-persist-files-and-wait (mock/stub (fn [file-ids _]
                                                      (swap! calls conj [:wait file-ids])
                                                      (rx/empty)))
        wasm.api/export-target           (mock/stub (constantly :client))
        de/collect-export-objects        (mock/stub (fn [_ _] {}))
        mw/ask-many!                     (mock/stub (fn [_]
                                                      (swap! calls conj [:export])
                                                      (rx/empty)))
        i18n/tr                          (mock/stub #(str "translated:" %))
        st/ongoing-tasks                 (atom #{})}
       (ptk/emit! store (de/request-simple-export {:export (assoc export :file-id file-id)}))
       (await (async/wait-for #(= 2 (count @calls)) "the export runs"))
       (t/is (= [[:wait #{file-id}] [:export]] @calls)
             "the export starts once the save is done")
       (t/is (= "translated:workspace.export.unsaved-changes-included"
                (get-in @store [:notification :content])))))))

;; Scenario: a shape is drawn in the browser with every change saved.
;; Proves: no warning is shown.
(t/deftest ^:async client-export-shows-no-warning-when-the-file-is-saved
  (let [exported (atom false)
        store    (export-store (wasm-state) (atom []))]
    (await
     (mock/with-mocks*
       {dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        wasm.api/export-target           (mock/stub (constantly :client))
        de/collect-export-objects        (mock/stub (fn [_ _] {}))
        mw/ask-many!                     (mock/stub (fn [_]
                                                      (reset! exported true)
                                                      (rx/empty)))
        st/ongoing-tasks                 (atom #{})}
       (ptk/emit! store (de/request-simple-export {:export (assoc export :file-id (uuid/next))}))
       (await (async/wait-for #(true? @exported) "the export runs"))
       (await (async/settle))
       (t/is (nil? (:notification @store)))))))
