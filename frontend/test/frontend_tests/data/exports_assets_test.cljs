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
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as h]
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
    (await (h/observe (ptk/watch (de/export-selected-shape) state nil)
                      :on-next #(swap! events conj %)))
    @events))

(t/deftest ^:async export-selected-shape-uses-single-preset
  (doseq [type [:png :jpeg :webp :svg :pdf]]
    (let [preset    {:type type :scale 2 :suffix "@2x"}
          state     (selected-shape-state [preset])
          events    (await (selected-shape-events state))
          request   (first events)
          observed  (atom nil)
          downloads (atom [])]
      (t/is (= [::de/request-simple-export ::ev/event]
               (mapv ptk/type events)))
      (t/is (= "workspace:shortcuts" (::ev/origin @(second events))))
      (t/is (= 1 (:num-shapes @(second events))))
      (t/is (= 1 (get @(second events) type)))
      (await
       (mock/with-mocks*
         {repo/cmd! (mock/stub (fn [_ params]
                                 (reset! observed params)
                                 (->> (rx/of {:filename "Asset@2x"
                                              :mtype "image/png"
                                              :uri "blob:export"})
                                      (rx/observe-on :async))))
          dwp/force-persist-and-wait (mock/stub (fn [_] (rx/empty)))
          dom/trigger-download-uri (mock/stub #(swap! downloads conj [%1 %2 %3]))}
         (await (h/observe (ptk/watch request state nil)))
         (t/is (= [{:type type
                    :scale 2
                    :suffix "@2x"
                    :page-id (:current-page-id state)
                    :file-id (:current-file-id state)
                    :object-id (:object-id export)
                    :name "Asset@2x"}]
                  (:exports @observed)))
         (t/is (true? (:wait @observed)))
         (t/is (= [["Asset@2x" "image/png" "blob:export"]] @downloads)))))))

(t/deftest ^:async export-selected-shape-uses-multiple-presets
  (let [presets  [{:type :png :scale 2 :suffix "@2x"}
                  {:type :jpeg :scale 0.5 :suffix "-small"}
                  {:type :webp :scale 3 :suffix "-web"}
                  {:type :svg :scale 1 :suffix "-vector"}
                  {:type :pdf :scale 1 :suffix "-print"}]
        state    (selected-shape-state presets)
        events   (await (selected-shape-events state))
        observed (atom nil)]
    (t/is (= [::de/request-multiple-export ::ev/event]
             (mapv ptk/type events)))
    (t/is (= {::ev/name "export-shapes"
              ::ev/origin "workspace:shortcuts"
              :num-shapes 5 :png 1 :jpeg 1 :webp 1 :svg 1 :pdf 1}
             @(second events)))
    (await
     (mock/with-mocks*
       {repo/cmd! (mock/stub (fn [_ params]
                               (reset! observed params)
                               (->> (rx/of {:id (uuid/next)})
                                    (rx/observe-on :async))))
        ws/get-rcv-stream (mock/stub (fn [_] (rx/empty)))
        st/ongoing-tasks (atom #{})}
       (await (h/observe (ptk/watch (first events) state nil)))
       (t/is (= presets (mapv #(select-keys % [:type :scale :suffix])
                              (:exports @observed))))
       (doseq [payload (:exports @observed)]
         (t/is (= {:page-id (:current-page-id state)
                   :file-id (:current-file-id state)
                   :object-id (:object-id export)
                   :name "Asset"}
                  (select-keys payload [:page-id :file-id :object-id :name]))))
       (t/is (true? (:force-multiple @observed)))))))

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


(t/deftest ^:async export-selected-shape-reuses-wasm-export-path
  (doseq [type [:png :jpeg :webp :svg :pdf]]
    (let [preset   {:type type :scale 2 :suffix "@2x"}
          state    (assoc (selected-shape-state [preset]) :features #{"render-wasm/v1"})
          events   (await (selected-shape-events state))
          observed (atom nil)
          effects  (atom [])
          capture  (mock/stub #(reset! observed %))]
      (await
       (mock/with-mocks*
         {wasm.exports/export-image capture
          wasm.exports/export-svg capture
          wasm.exports/export-pdf capture}
         (await (h/observe (ptk/watch (first events) state nil)
                           :on-next #(swap! effects conj %)))
         (t/is (= [::de/request-simple-export-wasm] (mapv ptk/type @effects)))
         (ptk/effect (first @effects) state nil)
         (t/is (= {:type type :scale 2 :suffix "@2x"
                   :name "Asset@2x" :object-id (:object-id export)
                   :file-id (:current-file-id state) :page-id (:current-page-id state)}
                  @observed)))))))
