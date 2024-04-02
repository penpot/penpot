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
   [app.plugins.utils :as utils]))

(def ^:private
  xf-map-page-proxy
  (comp
   (map val)
   (map page/data->page-proxy)))

(deftype FileProxy [id name revn
                    #_:clj-kondo/ignore _data]
  Object
  (getPages [_]
    ;; Returns a lazy (iterable) of all available pages
    (apply array (sequence xf-map-page-proxy (:pages-index _data)))))

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))}
  {:name "pages"
   :get (fn [] (this-as this (.getPages ^js this)))})

(defn data->file-proxy
  [file data]
  (utils/hide-data!
   (->FileProxy (str (:id file))
                (:name file)
                (:revn file)
                data)))


