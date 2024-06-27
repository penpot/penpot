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
   [app.plugins.parser :as parser]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(deftype PageProxy [$plugin $file $id]
  Object
  (getShapeById
    [_ shape-id]
    (cond
      (not (string? shape-id))
      (u/display-not-valid :getShapeById shape-id)

      :else
      (let [shape-id (uuid/uuid shape-id)]
        (shape/shape-proxy $plugin $file $id shape-id))))

  (getRoot
    [_]
    (shape/shape-proxy $plugin $file $id uuid/zero))

  (findShapes
    [_ criteria]
    ;; Returns a lazy (iterable) of all available shapes
    (let [criteria (parser/parse-criteria criteria)
          match-criteria?
          (if (some? criteria)
            (fn [[_ shape]]
              (and
               (or (not (:name criteria))
                   (= (str/lower (:name criteria)) (str/lower (:name shape))))

               (or (not (:name-like criteria))
                   (str/includes? (str/lower (:name shape)) (str/lower (:name-like criteria))))

               (or (not (:type criteria))
                   (= (:type criteria) (:type shape)))))
            identity)]
      (when (and (some? $file) (some? $id))
        (let [page (u/locate-page $file $id)
              xf (comp
                  (filter match-criteria?)
                  (map #(shape/shape-proxy $plugin $file $id (first %))))]
          (apply array (sequence xf (:objects page)))))))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :page-plugin-data-key key)

      :else
      (let [page (u/proxy->page self)]
        (dm/get-in page [:options :plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not (string? key))
      (u/display-not-valid :page-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :page-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :page $id (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [page (u/proxy->page self)]
      (apply array (keys (dm/get-in page [:options :plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :page-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :page-plugin-data-key key)

      :else
      (let [page (u/proxy->page self)]
        (dm/get-in page [:options :plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (not (string? namespace))
      (u/display-not-valid :page-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :page-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :page-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :page $id (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :page-plugin-data-namespace namespace)

      :else
      (let [page (u/proxy->page self)]
        (apply array (keys (dm/get-in page [:options :plugin-data (keyword "shared" namespace)])))))))

(crc/define-properties!
  PageProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "PageProxy"))})

(defn page-proxy? [p]
  (instance? PageProxy p))

(defn page-proxy
  [plugin-id file-id id]
  (crc/add-properties!
   (PageProxy. plugin-id file-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % u/proxy->page :name)
    :set
    (fn [_ value]
      (cond
        (not (string? value))
        (u/display-not-valid :page-name value)

        :else
        (st/emit! (dw/rename-page id value))))}

   {:name "root"
    :enumerable false
    :get #(.getRoot ^js %)}

   {:name "background"
    :enumerable false
    :get #(or (-> % u/proxy->page :options :background) cc/canvas)
    :set
    (fn [_ value]
      (cond
        (or (not (string? value)) (not (cc/valid-hex-color? value)))
        (u/display-not-valid :page-background-color value)

        :else
        (st/emit! (dw/change-canvas-color id {:color value}))))}))
