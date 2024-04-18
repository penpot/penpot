;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.file
  "RPC for plugins runtime."
  (:require
   [app.common.record :as crc]
   [app.plugins.page :as page]
   [app.plugins.utils :refer [get-data-fn]]))

(def ^:private
  xf-map-page-proxy
  (comp
   (map val)
   (map page/data->page-proxy)))

(deftype FileProxy [#_:clj-kondo/ignore _data]
  Object
  (getPages [_]
    ;; Returns a lazy (iterable) of all available pages
    (apply array (sequence xf-map-page-proxy (:pages-index _data)))))

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))})

(defn data->file-proxy
  [file data]
  (crc/add-properties!
   (FileProxy. (merge file data))
   {:name "_data" :enumerable false}

   {:name "id"
    :get (get-data-fn :id str)}

   {:name "name"
    :get (get-data-fn :name)}

   {:name "pages"
    :get #(.getPages ^js %)}))


