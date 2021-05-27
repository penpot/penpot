;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.zip
  "Helpers for make zip file (using jszip)."
  (:require
   ["jszip" :as zip]
   [app.common.data :as d]
   [beicon.core :as rx]
   [promesa.core :as p]))

(defn compress-files
  [files]
  (letfn [(attach-file [zobj [name content]]
            (.file zobj name content))]
    (let [zobj (zip.)]
      (run! (partial attach-file zobj) files)
      (->> (.generateAsync zobj #js {:type "blob"})
           (rx/from)))))

(defn extract-files
  "Creates a stream that will emit values for every file in the zip"
  [file]
  (rx/create
   (fn [subs]
     (let [process-entry
           (fn [path entry]
             (if (.-dir entry)
               (rx/push! subs {:dir path})
               (p/then
                (.async entry "text")
                (fn [content]
                  (rx/push! subs
                            {:path path
                             :content content})))))]

       (p/let [response (js/fetch file)
               data     (.blob response)
               content  (zip/loadAsync data)]

         (let [promises (atom [])]
           (.forEach content
                     (fn [path entry]
                       (let [current (process-entry path entry)]
                         (swap! promises conj current))))

           (p/then (p/all @promises)
                   #(rx/end! subs))))
       nil))))
