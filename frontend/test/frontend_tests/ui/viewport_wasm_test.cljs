;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.viewport-wasm-test
  (:require
   [app.main.errors :as errors]
   [app.main.ui.workspace.viewport-wasm :as viewport-wasm]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]))

(defn- wasm-error
  [code]
  (ex-info "WASM Error" {:type :wasm-error :code code :fn "_set_guides"}))

(defn- call
  "Runs `call-renderer!` with the renderer `ready?` and `f`. Returns the
  result, the errors sent to the app error handler and the calls to `f`."
  [ready? f]
  (let [reported (atom [])
        calls    (atom 0)]
    (with-redefs [wasm.api/initialized? (constantly ready?)
                  errors/on-error       (fn [cause] (swap! reported conj cause))]
      {:result   (viewport-wasm/call-renderer!
                  (fn [] (swap! calls inc) (f)))
       :reported @reported
       :calls    @calls})))

(t/deftest does-not-call-the-renderer-when-it-is-not-ready
  (let [{:keys [result reported calls]} (call false (constantly :done))]
    (t/is (nil? result))
    (t/is (zero? calls))
    (t/is (empty? reported))))

(t/deftest calls-the-renderer-when-it-is-ready
  (let [{:keys [result reported calls]} (call true (constantly :done))]
    (t/is (= :done result))
    (t/is (= 1 calls))
    (t/is (empty? reported))))

(t/deftest sends-wasm-errors-to-the-app-error-handler
  (doseq [code [:wasm-critical :panic]]
    (let [cause (wasm-error code)
          {:keys [result reported]} (call true #(throw cause))]
      (t/is (nil? result))
      (t/is (= [cause] reported)))))

(t/deftest rethrows-other-errors
  (let [cause (js/Error. "not a wasm error")]
    (t/is (= cause
             (try
               (call true #(throw cause))
               nil
               (catch :default e e))))))
