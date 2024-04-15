;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.record :as crc]
   [app.plugins.utils :as utils]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn- make-fills
  [fills]
  ;; TODO: Transform explicitly?
  (apply array
         (->> fills
              (map #(clj->js % {:keyword-fn (fn [k] (str/camel (name k)))})))))

(deftype ShapeProxy [_data]
  Object
  (clone [_] (.log js/console (clj->js _data)))
  (delete [_] (.log js/console (clj->js _data)))
  (appendChild [_] (.log js/console (clj->js _data))))

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})


(defn get-data
  ([this attr]
   (-> this
       (obj/get "_data")
       (get attr)))
  ([this attr transform-fn]
   (-> this
       (get-data attr)
       (transform-fn))))

(defn data->shape-proxy
  [data]

  (-> (->ShapeProxy data)
      (js/Object.defineProperties
       #js {"_data" #js {:enumerable false}

            :id
            #js {:get #(get-data (js* "this") :id str)
                 :enumerable true}

            :name
            #js {:get #(get-data (js* "this") :name)
                 ;;:set (fn [] (prn "SET NAME"))
                 :enumerable true}

            :fills
            #js {:get #(get-data (js* "this") :fills make-fills)
                 ;;:set (fn [] (prn "SET FILLS"))
                 :enumerable true}}
       )))

