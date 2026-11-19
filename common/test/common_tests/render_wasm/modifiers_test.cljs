;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.render-wasm.modifiers-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.render-wasm.api.modifiers :as modifiers]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.test-helpers.render-wasm :as thw]
   [clojure.test :as t]))

(def id-a #uuid "f1234567-89ab-cdef-fedc-ba9876543210")
(def id-b #uuid "12345678-abcd-ef01-2345-6789abcdef01")
(def matrix-a (gmt/matrix 0.1 -0.2 3.75 4.125 120.5 -80.25))
(def matrix-b (gmt/matrix -1 0.5 0.25 2 32 -64))
(defn- f32-matrix
  [matrix]
  (gmt/matrix (js/Math.fround (:a matrix))
              (js/Math.fround (:b matrix))
              (js/Math.fround (:c matrix))
              (js/Math.fround (:d matrix))
              (js/Math.fround (:e matrix))
              (js/Math.fround (:f matrix))))

(def requested [[id-a {:transform matrix-a :kind :child}]
                [id-b {:transform matrix-b :kind :parent}]])

(t/deftest propagation-results-can-be-applied-after-memory-growth
  (let [output [[id-b matrix-a] [id-a matrix-b]]
        {:keys [module state]} (thw/create-module {:output output
                                                   :grow-on-propagate true
                                                   :grow-on-alloc true})]
    (with-redefs [wasm/internal-module module
                  wasm/serializers thw/serializers]
      (let [result   (modifiers/propagate-modifiers requested false nil)
            expected (mapv (fn [[id matrix]] [id (f32-matrix matrix)]) output)
            inputs   (mapv (fn [[id data]] [id (update data :transform f32-matrix)])
                           requested)]
        (t/is (= expected result))
        (t/is (= {:precision 0 :entries inputs} (:propagated @state)))
        (modifiers/set-modifiers result)
        (t/is (= expected (:preview @state)))))))

(t/deftest pixel-snapping-respects-the-ignored-axis
  (doseq [[snap ignore-axis precision] [[false nil 0]
                                        [false :x 0]
                                        [true nil 1]
                                        [true :x 3]
                                        [true :y 2]]]
    (let [{:keys [module state]} (thw/create-module {})]
      (with-redefs [wasm/internal-module module
                    wasm/serializers thw/serializers]
        (modifiers/propagate-modifiers requested snap ignore-axis)
        (t/is (= precision (get-in @state [:propagated :precision])))))))

(t/deftest empty-propagation-result-releases-the-payload
  (let [{:keys [module state]} (thw/create-module {})]
    (with-redefs [wasm/internal-module module
                  wasm/serializers thw/serializers]
      (t/is (= [] (modifiers/propagate-modifiers requested false nil)))
      (modifiers/set-modifiers [[id-a matrix-a]])
      (t/is (= [[id-a (f32-matrix matrix-a)]] (:preview @state))))))

(t/deftest structure-entries-preserve-order-and-default-index
  (let [entries [{:type :remove-children :parent id-a :id id-b :value 0}
                 {:type :add-children :index 7 :parent id-b :id id-a :value 2.5}
                 {:type :scale-content :index 2 :parent id-a :id id-b :value 0.1}]
        {:keys [module state]} (thw/create-module {:grow-on-alloc true})]
    (with-redefs [wasm/internal-module module]
      (modifiers/set-structure-modifiers entries)
      (t/is (= (-> entries
                   (assoc-in [0 :index] 0)
                   (update-in [2 :value] js/Math.fround))
               (:structure @state))))))

(t/deftest empty-inputs-preserve-an-existing-preview
  (let [{:keys [module state]} (thw/create-module {})]
    (with-redefs [wasm/internal-module module
                  wasm/serializers thw/serializers]
      (modifiers/set-modifiers [[id-a matrix-a]])
      (modifiers/set-structure-modifiers
       [{:type :add-children :index 1 :parent id-a :id id-b :value 0}])
      (let [before @state]
        (t/is (= [] (modifiers/propagate-modifiers [] true nil)))
        (modifiers/set-modifiers [])
        (modifiers/set-structure-modifiers [])
        (t/is (= before @state))))))

(t/deftest native-propagation-errors-preserve-diagnostics-and-release-input
  (let [{:keys [module state]} (thw/create-module {:propagate-error 1})]
    (with-redefs [wasm/internal-module module
                  wasm/serializers thw/serializers]
      (let [error (try
                    (modifiers/propagate-modifiers requested false nil)
                    (catch :default cause cause))]
        (t/is (= {:type :wasm-error :code :non-blocking :fn "_propagate_modifiers"}
                 (select-keys (ex-data error) [:type :code :fn]))))
      (modifiers/set-modifiers [[id-b matrix-b]])
      (t/is (= [[id-b matrix-b]] (:preview @state))))))

(t/deftest undecodable-output-releases-the-payload
  (let [{:keys [module state]} (thw/create-module {:output [[id-a matrix-a]]
                                                   :invalid-output-pointer true})]
    (with-redefs [wasm/internal-module module
                  wasm/serializers thw/serializers]
      (t/is (thrown? js/Error (modifiers/propagate-modifiers requested false nil)))
      (modifiers/set-modifiers [[id-b matrix-b]])
      (t/is (= [[id-b matrix-b]] (:preview @state))))))
