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
   [app.plugins.page :as page]
   [app.plugins.utils :refer [locate-file proxy->file]]
   [app.util.object :as obj]))

(deftype FileProxy [$id]
  Object
  (getPages [_]
    (let [file (locate-file $id)]
      (apply array (sequence (map #(page/page-proxy $id %)) (dm/get-in file [:data :pages]))))))

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))})

(defn file-proxy
  [id]
  (crc/add-properties!
   (FileProxy. id)
   {:name "$id" :enumerable false :get (constantly id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % proxy->file :name)}

   {:name "pages"
    :get #(.getPages ^js %)}))


