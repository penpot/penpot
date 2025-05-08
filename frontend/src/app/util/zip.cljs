;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.zip
  "Helpers for make zip file (using jszip)."
  (:require
   ["jszip" :as zip]
   [promesa.core :as p]))

(defn load
  [data]
  (zip/loadAsync data))

(defn get-file
  "Gets a single file from the zip archive"
  ([zip path]
   (get-file zip path "text"))

  ([zip path type]
   (let [entry (.file zip path)]
     (cond
       (nil? entry)
       (p/rejected (str "File not found: " path))

       (.-dir ^js entry)
       (p/resolved {:dir path})

       :else
       (->> (.async ^js entry type)
            (p/fmap (fn [content]
                      ;; (js/console.log "zip:process-file" 2 content)
                      {:path path
                       :content content})))))))
