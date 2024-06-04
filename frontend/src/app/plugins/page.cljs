;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.page
  "RPC for plugins runtime."
  (:require
   [app.common.colors :as cc]
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
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
    (when (and (some? $file) (some? $id))
      (let [page (u/locate-page $file $id)]
        (apply array (sequence (map shape/shape-proxy) (keys (:objects page))))))))

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
    :get #(-> % u/proxy->page :name)
    :set
    (fn [_ value]
      (if (string? value)
        (st/emit! (dw/rename-page id value))
        (u/display-not-valid :page-name value)))}

   {:name "root"
    :enumerable false
    :get #(.getRoot ^js %)}

   {:name "background"
    :enumerable false
    :get #(or (-> % u/proxy->page :options :background) cc/canvas)
    :set
    (fn [_ value]
      (if (and (some? value) (string? value) (cc/valid-hex-color? value))
        (st/emit! (dw/change-canvas-color id {:color value}))
        (u/display-not-valid :page-background-color value)))}))
