;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.wasm.impl.embed
  "WASM binary embedding helpers."
  (:require
   [datoteka.io :as io])
  (:import
   java.util.Base64
   java.util.Base64$Encoder))

(defn- bytes->str
  [data]
  (String. ^bytes data "UTF-8"))

(defn- bytes->b64
  [data]
  (let [encoder (java.util.Base64/getEncoder)]
    (.encode ^Base64$Encoder encoder
             ^bytes data)))

(defmacro read-as-base64
  [source]
  (let [rsc (io/resource source)]
    (with-open [stream (io/input-stream rsc)]
      (let [data (io/read-as-bytes stream)]
        (-> data bytes->b64 bytes->str)))))
