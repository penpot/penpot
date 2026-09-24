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
   [app.main.data.persistence :as dwp]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
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
                        dwp/force-persist-and-wait (mock/stub (fn [_] (rx/of ::force-persisted)))
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
                  dwp/force-persist-and-wait (mock/stub (fn [_] (rx/empty)))
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
                     :name "Icon@2x" :enabled true
                     :file-id (:current-file-id state)
                     :page-id (:current-page-id state)
                     :object-id (:id shape)
                     :shape (dissoc shape :exports)}]
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
          (t/is (= [(:id shape) (:id shape)] (mapv :object-id (:exports params)))))
        (rx/push! response {:id (:id export)})
        (t/is (= (:id export) (get-in @store [:export :resource-id])))
        (t/is (= 2 (count (get-in @store [:export :exports]))))
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

(t/deftest export-selection-wasm-downloads-have-exactly-one-suffix
  (doseq [[format filename mtype] [[:png "Icon@2x" "image/png"]
                                   [:jpeg "Icon@2x" "image/jpeg"]
                                   [:webp "Icon@2x" "image/webp"]
                                   [:pdf "Icon@2x.pdf" "application/pdf"]
                                   [:svg "Icon@2x.svg" "image/svg+xml"]]]
    (let [shape     (selected-shape "Icon" [{:type format :scale 2 :suffix "@2x"}])
          state     (assoc (selection-state [shape]) :features #{"render-wasm/v1"})
          renders   (atom [])
          downloads (atom [])
          errors    (atom [])
          store     (ptk/store {:state state :on-error #(swap! errors conj %)})
          render    (fn [id scale & [format]]
                      (swap! renders conj [id scale format])
                      (js/Uint8Array. #js [1 2 3]))]
      (with-redefs [cf/flags #{:wasm-export}
                    wasm.api/render-shape-pixels (mock/stub render)
                    wasm.api/render-shape-pdf (mock/stub render)
                    wasm.api/render-shape-svg (mock/stub render)
                    dom/trigger-download-uri (mock/stub (fn [name mtype _uri]
                                                          (swap! downloads conj [name mtype])))]
        (try
          (ptk/emit! store (de/export-selected-shapes {:origin "workspace:shortcut"}))
          (t/is (= [[filename mtype]] @downloads))
          (t/is (= [[(:id shape) 2 (when (#{:png :jpeg :webp} format) format)]] @renders))
          (t/is (nil? (:export @store)))
          (t/is (= (:files state) (:files @store)))
          (t/is (empty? @errors))
          (finally
            (rx/dispose! store)))))))
