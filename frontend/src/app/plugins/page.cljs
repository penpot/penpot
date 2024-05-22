;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.page
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.common.uuid :as uuid]
   [app.plugins.shape :as shape]
   [app.plugins.utils :refer [locate-page proxy->page]]
   [app.util.object :as obj]))

(deftype PageProxy [$file $id]
  Object
  (getShapeById
    [_ shape-id]
    (let [shape-id (uuid/uuid shape-id)]
      (shape/shape-proxy $file $id shape-id)))

  (getRoot
    [_]
    (shape/shape-proxy $file $id uuid/zero))

  (findShapes
    [_]
    ;; Returns a lazy (iterable) of all available shapes
    (let [page (locate-page $file $id)]
      (apply array (sequence (map shape/shape-proxy) (keys (:objects page)))))))

(crc/define-properties!
  PageProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "PageProxy"))})

(defn page-proxy
  [file-id id]
  (crc/add-properties!
   (PageProxy. file-id id)
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % proxy->page :name)}

   {:name "root"
    :enumerable false
    :get #(.getRoot ^js %)}))
