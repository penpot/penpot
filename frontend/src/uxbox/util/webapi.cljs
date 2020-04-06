;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.webapi
  "HTML5 web api helpers."
  (:require
   [promesa.core :as p]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [uxbox.util.transit :as t]))

(defn read-file-as-text
  [file]
  (rx/create
   (fn [sink]
     (let [fr (js/FileReader.)]
       (aset fr "onload" #(sink (rx/end (.-result fr))))
       (.readAsText fr file)
       (constantly nil)))))

(defn read-file-as-dataurl
  [file]
  (rx/create
   (fn [sick]
     (let [fr (js/FileReader.)]
       (aset fr "onload" #(sick (rx/end (.-result fr))))
       (.readAsDataURL fr file))
     (constantly nil))))

(defn ^boolean blob?
  [v]
  (instance? js/Blob v))

(defn create-blob
  "Create a blob from content."
  ([content]
   (create-blob content "application/octet-stream"))
  ([content mtype]
   (js/Blob. #js [content] #js {:type mtype})))

(defn revoke-uri
  [url]
  (assert (string? url) "invalid arguments")
  (js/URL.revokeObjectURL url))

(defn create-uri
  "Create a url from blob."
  [b]
  (assert (blob? b) "invalid arguments")
  (js/URL.createObjectURL b))


;; (defn get-image-size
;;   [file]
;;   (letfn [(on-load [sink img]
;;             (let [size [(.-width img) (.-height img)]]
;;               (sink (rx/end size))))
;;           (on-subscribe [sink]
;;             (let [img (js/Image.)
;;                   uri (blob/create-uri file)]
;;               (set! (.-onload img) (partial on-load sink img))
;;               (set! (.-src img) uri)
;;               #(blob/revoke-uri uri)))]
;;     (rx/create on-subscribe)))


(defn write-to-clipboard
  [data]
  (let [cboard (unchecked-get js/navigator "clipboard")]
    (.writeText cboard (uxbox.util.transit/encode data))))

(defn- read-from-clipboard
  []
  (let [cboard (unchecked-get js/navigator "clipboard")]
    (-> (.readText cboard)
        (p/then (fn [data]
                  (try
                    (t/decode data)
                    (catch :default e
                      nil)))))))

(defn request-fullscreen
  [el]
  (.requestFullscreen el))

(defn exit-fullscreen
  []
  (.exitFullscreen js/document))
