;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.performance
  #?(:cljs (:require-macros [app.render-wasm.performance]))
  (:require
   [cuerdas.core :as str]))

(defn enabled?
  []
  #?(:clj (= (System/getProperty "penpot.wasm.profile-marks") "true")
     :cljs false))

(defmacro begin-measure
  [measure-name]
  (when enabled?
    (let [measure-name (str/concat measure-name "::begin")]
      `(.mark js/performance ~measure-name))))

(defmacro end-measure
  [measure-name & [detail]]
  (when enabled?
    (let [begin-name (str/concat measure-name "::begin")
          end-name (str/concat measure-name "::end")
          detail `(cljs.core/js-obj ~@(mapcat (fn [[k v]] [(name k) v]) detail))
          options `(cljs.core/js-obj "start" ~begin-name "end" ~end-name "detail" ~detail)]
      `(do (.mark js/performance ~end-name)
           (.measure js/performance ~measure-name ~options)))))

(defmacro with-measure
  "Measures the time of a function call. This should only be called in synchronous functions"
  [[measure-name detail] body]
  (if-not enabled?
    body
    `(let [_# (begin-measure ~measure-name)
           result# ~body
           _# (end-measure ~measure-name ~detail)]
       result#)))
