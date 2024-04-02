;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.page
  "RPC for plugins runtime."
  (:require
   [app.common.record :as crc]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as utils]))

(def ^:private
  xf-map-shape-proxy
  (comp
   (map val)
   (map shape/data->shape-proxy)))

(deftype PageProxy [id name
                    #_:clj-kondo/ignore _data]
  Object
  (findShapes [_]
    ;; Returns a lazy (iterable) of all available shapes
    (apply array (sequence xf-map-shape-proxy (:objects _data)))))

(crc/define-properties!
  PageProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "PageProxy"))})

(defn data->page-proxy
  [data]
  (utils/hide-data!
   (->PageProxy
    (str (:id data))
    (:name data)
    data)))
