;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.transit
  (:require
   [cognitect.transit :as t]))

;; --- Transit Handlers

(def ^:private +read-handlers+
  {"u" uuid})

(def ^:private +write-handlers+
  {})

;; --- Public Api

(defn decode
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  [data]
  (let [w (t/writer :json {:handlers +write-handlers+})]
    (t/write w data)))
