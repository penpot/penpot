;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.page
  "RPC for plugins runtime."
  (:require
   [app.common.record :as crc]
   [app.common.uuid :as uuid]
   [app.plugins.shape :as shape]
   [app.plugins.utils :refer [get-data-fn]]))

(def ^:private
  xf-map-shape-proxy
  (comp
   (map val)
   (map shape/data->shape-proxy)))

(deftype PageProxy [#_:clj-kondo/ignore _data]
  Object
  (getShapeById [_ id]
    (shape/data->shape-proxy (get (:objects _data) (uuid/uuid id))))

  (getRoot [_]
    (shape/data->shape-proxy (get (:objects _data) uuid/zero)))

  (findShapes [_]
    ;; Returns a lazy (iterable) of all available shapes
    (apply array (sequence xf-map-shape-proxy (:objects _data)))))

(crc/define-properties!
  PageProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "PageProxy"))})

(defn data->page-proxy
  [data]

  (crc/add-properties!
   (PageProxy. data)
   {:name "_data" :enumerable false}

   {:name "id"
    :get (get-data-fn :id str)}

   {:name "name"
    :get (get-data-fn :name)}

   {:name "root"
    :get #(.getRoot ^js %)}))
