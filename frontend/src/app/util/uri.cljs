;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.uri
  (:require
   [cuerdas.core :as str]
   [app.util.object :as obj]))

(defn uri-name [url]
  (let [query-idx (str/last-index-of url "?")
        url (if (> query-idx 0) (subs url 0 query-idx) url)
        filename (->> (str/split url "/") (last))
        ext-idx (str/last-index-of filename ".")]
    (if (> ext-idx 0) (subs filename 0 ext-idx) filename)))

(defn data-uri->blob
  [data-uri]

  (let [[mtype b64-data] (str/split data-uri ";base64,")

        mtype (subs mtype (inc (str/index-of mtype ":")))

        decoded (.atob js/window b64-data)
        size (.-length decoded)

        content (js/Uint8Array. size)]

    (doseq [i (range 0 size)]
      (obj/set! content i (.charCodeAt decoded i)))

    (js/Blob. #js [content] #js {"type" mtype})))
