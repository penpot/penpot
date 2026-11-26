;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.modifiers-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.render-wasm.api.modifiers :as modifiers]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.test-helpers.render-wasm :as thw]
   [app.render-wasm.api :as api]
   [app.util.timers :as timers]
   [cljs.test :as t :include-macros true]))

(def id-a #uuid "f1234567-89ab-cdef-fedc-ba9876543210")
(def id-b #uuid "12345678-abcd-ef01-2345-6789abcdef01")
(def transform (gmt/matrix 1 0 0 1 24 -16))

(defn- with-renderer
  [options f]
  (let [{:keys [module] :as env} (thw/create-module options)
        frames (atom [])]
    (with-redefs [wasm/internal-module module
                  wasm/serializers thw/serializers
                  wasm/context-initialized? true
                  wasm/context-lost? (atom false)
                  wasm/reloading? (atom false)
                  wasm/disable-request-render? (atom false)
                  wasm/internal-frame-id nil
                  timers/raf (fn [callback]
                               (swap! frames conj callback)
                               (count @frames))]
      (try
        (f (assoc env :frames frames))
        (finally
          (with-redefs [wasm/context-initialized? false]
            (doseq [callback @frames]
              (callback 0))))))))

(t/deftest unavailable-renderers-preserve-existing-modifiers
  (doseq [[initialized lost reloading] [[false false false]
                                        [true true false]
                                        [true false true]]
          interactive [false true]]
    (with-renderer
      {}
      (fn [{:keys [state frames]}]
        (modifiers/set-modifiers [[id-a transform]])
        (modifiers/set-structure-modifiers
         [{:type :add-children :parent id-a :id id-b :value 0}])
        (when interactive
          (modifiers/set-modifiers-start))
        (let [before @state]
          (with-redefs [wasm/context-initialized? initialized
                        wasm/context-lost? (atom lost)
                        wasm/reloading? (atom reloading)]
            (t/is (= [] (api/propagate-modifiers
                         [[id-a {:transform transform :kind :child}]] true nil)))
            (api/set-modifiers nil)
            (api/set-structure-modifiers
             [{:type :add-children :parent id-b :id id-a :index 9 :value 0}])
            (api/clean-modifiers)
            (api/set-modifiers-start)
            (api/set-modifiers-end)
            (t/is (= before @state))
            (t/is (empty? @frames))))))))

(t/deftest applying-a-preview-schedules-a-render
  (with-renderer
    {}
    (fn [{:keys [state frames]}]
      (api/set-modifiers [])
      (t/is (empty? @frames))
      (t/is (thrown? js/Error (api/set-modifiers nil)))
      (api/set-modifiers [[id-a transform] [id-b transform]])
      (t/is (= [[id-a transform] [id-b transform]] (:preview @state)))
      (t/is (= 1 (count @frames))))))

(t/deftest propagation-and-structure-updates-leave-rendering-explicit
  (let [output [[id-b transform]]]
    (with-renderer
      {:output output}
      (fn [{:keys [state frames]}]
        (let [entries [[id-a {:transform transform :kind :child}]]]
          (t/is (= output (api/propagate-modifiers entries true :x)))
          (t/is (= {:precision 3 :entries entries} (:propagated @state))))
        (api/set-structure-modifiers
         [{:type :add-children :parent id-a :id id-b :index 2 :value 0}])
        (t/is (= [{:type :add-children :parent id-a :id id-b :index 2 :value 0}]
                 (:structure @state)))
        (t/is (empty? @frames))))))

(t/deftest interactive-mode-and-preview-cleanup-have-independent-effects
  (let [first-preview    [[id-a transform]]
        first-structure  [{:type :add-children :parent id-a :id id-b :index 2 :value 0}]
        second-preview   [[id-b (gmt/matrix 1 0 0 1 -48 32)]]
        second-structure [{:type :remove-children :parent id-b :id id-a :index 1 :value 0}]
        state-keys       [:interactive :preview :structure]]
    (with-renderer
      {}
      (fn [{:keys [state frames]}]
        (api/set-modifiers-start)
        (modifiers/set-modifiers first-preview)
        (modifiers/set-structure-modifiers first-structure)
        (t/is (= {:interactive true :preview first-preview :structure first-structure}
                 (select-keys @state state-keys)))

        (api/clean-modifiers)
        (t/is (= {:interactive true :preview [] :structure []}
                 (select-keys @state state-keys)))

        (modifiers/set-modifiers second-preview)
        (modifiers/set-structure-modifiers second-structure)
        (api/set-modifiers-end)
        (t/is (= {:interactive false :preview second-preview :structure second-structure}
                 (select-keys @state state-keys)))
        (t/is (empty? @frames))))))

(t/deftest a-failed-preview-does-not-schedule-a-render
  (with-renderer
    {:set-error 2}
    (fn [{:keys [frames]}]
      (let [error (try
                    (api/set-modifiers [[id-a transform]])
                    (catch :default cause cause))]
        (t/is (= "_set_modifiers" (:fn (ex-data error))))
        (t/is (= :panic (:code (ex-data error))))
        (t/is (empty? @frames))))))
