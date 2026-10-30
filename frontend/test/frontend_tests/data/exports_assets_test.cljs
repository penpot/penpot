;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-assets-test
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.config :as cf]
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
    (let [export (assoc (export-with-name "")
                        :shape {:id "heavy-shape" :content (vec (range 1000))}
                        :enabled true)
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

(t/deftest request-multiple-export-omits-ui-only-shape-data
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
                                       :type :png
                                       :suffix ""
                                       :scale 1
                                       :name "enabled-1"}]
                                     (:exports @observed))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-multiple-export {:exports exports})
                       :the/end)))
        done))))

(defn- selected-shape
  [name presets]
  {:id (uuid/next)
   :type :rect
   :parent-id uuid/zero
   :name name
   :exports presets})

(defn- selection-state
  [shapes]
  (let [file-id (uuid/next)
        page-id (uuid/next)]
    (assoc (test-state)
           :current-file-id file-id
           :current-page-id page-id
           :workspace-local {:selected (into (d/ordered-set) (map :id) shapes)}
           :files {file-id {:data {:pages-index
                                   {page-id {:name "Assets"
                                             :objects (into {} (map (juxt :id identity)) shapes)}}}}})))

(t/deftest export-selection-does-not-start-without-eligible-shapes-or-while-busy
  (doseq [state [(selection-state [])
                 (selection-state [(selected-shape "Icon" [])])
                 (assoc (selection-state [(selected-shape "Icon" [{:type :png :scale 1 :suffix ""}])])
                        :export {:in-progress true :resource-id (:id export)})]]
    (let [calls  (atom [])
          errors (atom [])
          store  (ptk/store {:state state :on-error #(swap! errors conj %)})]
      (with-redefs [repo/cmd! (mock/stub (fn [& args]
                                           (swap! calls conj args)
                                           (rx/empty)))]
        (try
          (ptk/emit! store (de/export-selected-shapes {:origin "workspace:shortcut"}))
          (t/is (empty? @calls))
          (t/is (= :info (get-in @store [:notification :level])))
          (t/is (= (:export state) (:export @store)))
          (t/is (empty? @errors))
          (finally
            (rx/dispose! store)))))))

(t/deftest export-selection-downloads-single-preset-with-suffix-once
  (let [shape     (selected-shape "Icon" [{:type :png :scale 2 :suffix "@2x"}])
        state     (selection-state [shape])
        response  (rx/subject)
        calls     (atom [])
        downloads (atom [])
        errors    (atom [])
        store     (ptk/store {:state state :on-error #(swap! errors conj %)})]
    (with-redefs [cf/flags #{}
                  repo/cmd! (mock/stub (fn [cmd params]
                                         (swap! calls conj [cmd params])
                                         (rx/take 1 response)))
                  dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
                  dom/trigger-download-uri (mock/stub (fn [& args]
                                                        (swap! downloads conj args)))]
      (try
        (ptk/emit! store
                   (de/export-selected-shapes {:origin "workspace:shortcut"})
                   (de/export-selected-shapes {:origin "workspace:shortcut"}))
        (t/is (= 1 (count @calls)))
        (let [[cmd params] (first @calls)]
          (t/is (= :export cmd))
          (t/is (true? (:wait params)))
          (t/is (= [{:type :png :scale 2 :suffix "@2x"
                     :name "Icon@2x"
                     :file-id (:current-file-id state)
                     :page-id (:current-page-id state)
                     :object-id (:id shape)}]
                   (:exports params))))
        (t/is (true? (get-in @store [:export :in-progress])))
        (t/is (empty? @downloads))
        (rx/push! response {:filename "Icon@2x.png" :mtype "image/png" :uri "blob:icon"})
        (t/is (= [["Icon@2x.png" "image/png" "blob:icon"]] @downloads))
        (t/is (nil? (:export @store)))
        (t/is (= (:files state) (:files @store)))
        (t/is (empty? @errors))
        (finally
          (rx/dispose! store)
          (rx/end! response))))))

(t/deftest export-selection-reserves-batch-before-the-server-responds
  (let [shape    (selected-shape "Icon" [{:type :png :scale 1 :suffix ""}
                                         {:type :svg :scale 1 :suffix "-vector"}])
        state    (selection-state [shape (selected-shape "Unconfigured" [])])
        response (rx/subject)
        calls    (atom [])
        errors   (atom [])
        store    (ptk/store {:state state :on-error #(swap! errors conj %)})]
    (with-redefs [cf/flags #{}
                  repo/cmd! (mock/stub (fn [cmd params]
                                         (swap! calls conj [cmd params])
                                         (rx/take 1 response)))
                  dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
                  ws/get-rcv-stream (mock/stub (fn [_] (rx/empty)))
                  st/ongoing-tasks (atom #{})]
      (try
        (ptk/emit! store
                   (de/export-selected-shapes {:origin "workspace:menu"})
                   (de/export-selected-shapes {:origin "workspace:shortcut"}))
        (t/is (= 1 (count @calls)))
        (t/is (true? (get-in @store [:export :in-progress])))
        (let [[cmd params] (first @calls)]
          (t/is (= :export cmd))
          (t/is (= "Assets" (:name params)))
          (t/is (true? (:force-multiple params)))
          (t/is (= ["Icon" "Icon"] (mapv :name (:exports params))))
          (t/is (= ["" "-vector"] (mapv :suffix (:exports params))))
          (t/is (= [(:id shape) (:id shape)] (mapv :object-id (:exports params))))
          (t/is (every? #(not (contains? % :shape)) (:exports params)))
          (t/is (every? #(not (contains? % :enabled)) (:exports params))))
        (rx/push! response {:id (:id export)})
        (t/is (= (:id export) (get-in @store [:export :resource-id])))
        (t/is (= 2 (count (get-in @store [:export :exports]))))
        (t/is (every? :enabled (get-in @store [:export :exports])))
        (t/is (true? (get-in @store [:export :widget-visible])))
        (t/is (= (:files state) (:files @store)))
        (t/is (empty? @errors))
        (finally
          (rx/dispose! store)
          (rx/end! response))))))

(t/deftest export-selection-releases-batch-after-saturation-and-allows-retry
  (let [shape  (selected-shape "Icon" [{:type :png :scale 1 :suffix ""}
                                       {:type :png :scale 2 :suffix "@2x"}])
        calls  (atom 0)
        errors (atom [])
        store  (ptk/store {:state (selection-state [shape]) :on-error #(swap! errors conj %)})]
    (with-redefs [cf/flags #{}
                  repo/cmd! (mock/stub (fn [_ _]
                                         (if (= 1 (swap! calls inc))
                                           (rx/throw (ex-info "Exporter is busy" {:code :queue-full}))
                                           (rx/of {:id (:id export)}))))
                  dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
                  ws/get-rcv-stream (mock/stub (fn [_] (rx/empty)))
                  st/ongoing-tasks (atom #{})]
      (try
        (ptk/emit! store (de/export-selected-shapes {:origin "workspace:shortcut"}))
        (t/is (false? (get-in @store [:export :in-progress])))
        (t/is (= :queue-full (get-in @store [:export :error-code])))
        (ptk/emit! store (de/export-selected-shapes {:origin "workspace:shortcut"}))
        (t/is (= 2 @calls))
        (t/is (true? (get-in @store [:export :in-progress])))
        (t/is (false? (get-in @store [:export :error])))
        (t/is (empty? @errors))
        (finally
          (rx/dispose! store))))))

(t/deftest ^:async export-selection-wasm-downloads-have-exactly-one-suffix
  (doseq [[format filename mtype] [[:png "Icon@2x.png" "image/png"]
                                   [:jpeg "Icon@2x.jpg" "image/jpeg"]
                                   [:webp "Icon@2x.webp" "image/webp"]
                                   [:pdf "Icon@2x.pdf" "application/pdf"]
                                   [:svg "Icon@2x.svg" "image/svg+xml"]]]
    (let [shape     (selected-shape "Icon" [{:type format :scale 2 :suffix "@2x"}])
          state     (assoc (selection-state [shape]) :features #{"render-wasm/v1"})
          requests  (atom [])
          downloads (atom [])
          errors    (atom [])
          store     (ptk/store {:state state :on-error #(swap! errors conj %)})]
      (try
        (await
         (mock/with-mocks*
           {cf/flags                #{}
            st/ongoing-tasks         (atom #{})
            repo/cmd!               (mock/stub (fn [& _]
                                                 (t/is false "WASM exports stay local")
                                                 (rx/empty)))
            wasm.api/export-target  (mock/stub (constantly :client))
            mw/ask-many!            (mock/stub (fn [request]
                                                 (swap! requests conj request)
                                                 (rx/of {:type :done
                                                         :filename (wasm.exports/sanitize-filename
                                                                    (first (:exports request)) #{})
                                                         :mtype mtype
                                                         :blob (js/Blob. #js ["export"])})))
            wapi/create-uri         (mock/stub (constantly "blob:export"))
            dom/trigger-download-uri (mock/stub (fn [name mtype _uri]
                                                  (swap! downloads conj [name mtype])))}
           (ptk/emit! store (de/export-selected-shapes {:origin "workspace:shortcut"}))
           (await (async/wait-for #(seq @downloads) "the selected shape downloads"))
           (t/is (= [[filename mtype]] @downloads))
           (t/is (= 1 (count @requests)))
           (let [request (first @requests)]
             (t/is (= :exports/run-local (:cmd request)))
             (t/is (= :single (:kind request)))
             (t/is (= [{:object-id (:id shape) :type format :scale 2
                        :name "Icon" :suffix "@2x"}]
                      (:exports request)))
             (t/is (= shape (get (:objects request) (:id shape)))))
           (t/is (false? (get-in @store [:export :in-progress])))
           (t/is (= (:files state) (:files @store)))
           (t/is (empty? @errors))))
        (finally
          (rx/dispose! store))))))

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
       (await (async/wait-for #(some? (:notification @store)) "the toast shows"))
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
       (await (async/wait-for #(= "running" (get-in @store [:export :status]))
                              "export is running"))
       (rx/push! worker-bus {:type :progress :done 1 :total 2})
       (await (async/wait-for #(= 1 (get-in @store [:export :progress]))
                              "first progress lands"))
       (ptk/emit! store (de/cancel-export))
       (await (async/wait-for #(= "cancelled" (get-in @store [:export :status]))
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
