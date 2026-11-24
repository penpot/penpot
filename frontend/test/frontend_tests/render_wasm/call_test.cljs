;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.call-test
  "Unit tests for the `app.common.render-wasm.helpers/call` macro."
  (:require
   [app.common.render-wasm.helpers :as h]
   [cljs.test :as t :include-macros true]))

(defn- fake-module
  "Module whose `_fail` export throws like a WASM trap after moving the
  shadow stack pointer down 64 bytes, leaking the frame as a trap does."
  [error-code]
  (let [sp       (atom 1000)
        restored (atom [])]
    {:sp       sp
     :restored restored
     :module   #js {"stackSave"        (fn [] @sp)
                    "stackRestore"     (fn [v]
                                         (swap! restored conj v)
                                         (reset! sp v))
                    "_read_error_code" (fn [] error-code)
                    "_ok"              (fn [x] (* 2 x))
                    "_fail"            (fn []
                                         (swap! sp - 64)
                                         (throw (js/Error. "unreachable")))}}))

(t/deftest failed-call-restores-stack-pointer
  (let [{:keys [module sp restored]} (fake-module 0x02)]
    (dotimes [_ 3]
      (t/is (thrown? js/Error (h/call module "_fail"))))
    (t/is (= 1000 @sp))
    (t/is (= [1000 1000 1000] @restored))))

(t/deftest failed-call-keeps-error-classification
  (let [{:keys [module]} (fake-module 0x01)
        data             (try
                           (h/call module "_fail")
                           nil
                           (catch :default cause
                             (ex-data cause)))]
    (t/is (= :wasm-error (:type data)))
    (t/is (= :non-blocking (:code data)))
    (t/is (= "_fail" (:fn data)))))

(t/deftest successful-call-does-not-restore
  (let [{:keys [module restored]} (fake-module 0)]
    (t/is (= 42 (h/call module "_ok" 21)))
    (t/is (empty? @restored))))

(t/deftest module-without-stack-methods-still-reports-errors
  (let [module #js {"_read_error_code" (fn [] 0x02)
                    "_fail"            (fn [] (throw (js/Error. "unreachable")))}
        data   (try
                 (h/call module "_fail")
                 nil
                 (catch :default cause
                   (ex-data cause)))]
    (t/is (= :panic (:code data)))))
