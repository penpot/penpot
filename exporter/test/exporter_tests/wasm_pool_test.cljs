;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.wasm-pool-test
  "Worker leasing, against a stub pool: `with-worker` must give the worker back
  however its body ends."
  (:require
   [app.wasm.pool :as pool]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(defn- stub-pool!
  "Installs a pool whose acquire/release/destroy only count calls."
  []
  (let [calls (atom {:acquired 0 :released 0 :destroyed 0})]
    (reset! pool/pool
            #js {:acquire (fn [] (swap! calls update :acquired inc) (p/resolved ::worker))
                 :release (fn [_] (swap! calls update :released inc) (p/resolved nil))
                 :destroy (fn [_] (swap! calls update :destroyed inc) (p/resolved nil))})
    calls))

(t/deftest releases-the-worker-when-the-body-succeeds
  (t/async done
    (let [calls (stub-pool!)]
      (p/let [result (pool/with-worker (fn [_] (p/resolved :ok)))]
        (t/is (= :ok result))
        (t/is (= 1 (:acquired @calls)))
        (t/is (= 1 (:released @calls)))
        (t/is (= 0 (:destroyed @calls)))
        (reset! pool/pool nil)
        (done)))))

(t/deftest gives-the-worker-back-when-the-body-throws-synchronously
  (t/testing "a raise out of the scope body must not leave the worker borrowed"
    (t/async done
      (let [calls (stub-pool!)]
        (->> (pool/with-worker (fn [_] (throw (ex-info "cancelled" {}))))
             (p/hmap (fn [_ cause]
                       (t/is (some? cause))
                       (t/is (= 1 (:acquired @calls)))
                       (t/is (= 1 (+ (:released @calls) (:destroyed @calls))))
                       (reset! pool/pool nil)
                       (done))))))))
