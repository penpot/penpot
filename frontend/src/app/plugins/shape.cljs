;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.plugins.utils :as utils]
   [cuerdas.core :as str]))

(defn- fills
  [shape]
  ;; TODO: Transform explicitly?
  (apply array
         (->> (:fills shape)
              (map #(clj->js % {:keyword-fn (fn [k] (str/camel (name k)))})))))

(deftype ShapeProxy
    [id
     name
     type
     fills
     _data])

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})

(defn data->shape-proxy
  [data]
  (utils/hide-data!
   (->ShapeProxy (dm/str (:id data))
                 (:name data)
                 (d/name (:type data))
                 (fills data)
                 data)))

