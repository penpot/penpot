;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.local-storage
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.globals :as g]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defonce ^:private local-storage
  (ex/ignoring (unchecked-get g/global "localStorage")))

(defn prefix-key
  [plugin-id key]
  (dm/str "penpot-plugins:" plugin-id "/" key))

(defn local-storage-proxy
  [plugin-id]
  (obj/reify {:name "LocalStorageProxy"}
    :$plugin {:enumerable false :get (fn [] plugin-id)}

    :getItem
    (fn [key]
      (cond
        (not (r/check-permission plugin-id "allow:localstorage"))
        (u/display-not-valid :getItem "Plugin doesn't have 'allow:localstorage' permission")

        (not (string? key))
        (u/display-not-valid :getItem "The key must be a string")

        :else
        (.getItem ^js local-storage (prefix-key plugin-id key))))

    :setItem
    (fn [key value]
      (cond
        (not (r/check-permission plugin-id "allow:localstorage"))
        (u/display-not-valid :setItem "Plugin doesn't have 'allow:localstorage' permission")

        (not (string? key))
        (u/display-not-valid :setItem "The key must be a string")

        :else
        (.setItem ^js local-storage (prefix-key plugin-id key) value)))

    :removeItem
    (fn [key]
      (cond
        (not (r/check-permission plugin-id "allow:localstorage"))
        (u/display-not-valid :removeItem "Plugin doesn't have 'allow:localstorage' permission")

        (not (string? key))
        (u/display-not-valid :removeItem "The key must be a string")

        :else
        (.getItem ^js local-storage (prefix-key plugin-id key))))

    :getKeys
    (fn []
      (->> (.keys js/Object local-storage)
           (filter #(str/starts-with? % (prefix-key plugin-id "")))
           (map #(str/replace % (prefix-key plugin-id "") ""))
           (apply array)))))

