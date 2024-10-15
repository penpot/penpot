;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.zip
  "Helpers for make zip file (using jszip)."
  (:require
   ["jszip" :as zip]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(defn compress-files
  [files]
  (letfn [(attach-file [zobj [name content]]
            (.file zobj name content))]
    (let [zobj (zip.)]
      (run! (partial attach-file zobj) files)
      (->> (.generateAsync zobj #js {:type "blob"})
           (rx/from)))))

(defn load-from-url
  "Loads the data from a blob url"
  [url]
  (->> (http/send!
        {:uri url
         :response-type :blob
         :method :get})
       (rx/map :body)
       (rx/merge-map zip/loadAsync)))

(defn- process-file
  [entry path type]
  ;; (js/console.log "zip:process-file" entry path type)
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
                    :content content})))))

(defn load
  [data]
  (rx/from (zip/loadAsync data)))

(defn get-file
  "Gets a single file from the zip archive"
  ([zip path]
   (get-file zip path "text"))

  ([zip path type]
   (-> (.file zip path)
       (process-file path type)
       (rx/from))))

(defn extract-files
  "Creates a stream that will emit values for every file in the zip"
  [zip]
  (let [promises (atom [])
        get-file
        (fn [path entry]
          (let [current (process-file entry path "text")]
            (swap! promises conj current)))]
    (.forEach zip get-file)

    (->> (rx/from (p/all @promises))
         (rx/merge-map identity))))
