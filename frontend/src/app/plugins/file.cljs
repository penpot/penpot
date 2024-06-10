;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.file
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.plugins.page :as page]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(deftype FileProxy [$plugin $id]
  Object
  (getPages [_]
    (let [file (u/locate-file $id)]
      (apply array (sequence (map #(page/page-proxy $plugin $id %)) (dm/get-in file [:data :pages])))))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :file-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [file (u/proxy->file self)]
      (apply array (keys (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :file-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (not (string? namespace))
      (u/display-not-valid :file-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :file-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :file-plugin-data-namespace namespace)

      :else
      (let [file (u/proxy->file self)]
        (apply array (keys (dm/get-in file [:data :plugin-data (keyword "shared" namespace)])))))))

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))})

(defn file-proxy
  [plugin-id id]
  (crc/add-properties!
   (FileProxy. plugin-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % u/proxy->file :name)}

   {:name "pages"
    :get #(.getPages ^js %)}))


