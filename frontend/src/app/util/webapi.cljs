;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.webapi
  "HTML5 web api helpers."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [cuerdas.core :as str]))

(defn- file-reader
  [f]
  (rx/create
   (fn [subs]
     (let [reader (js/FileReader.)]
       (obj/set! reader "onload" #(do (rx/push! subs (.-result reader))
                                      (rx/end! subs)))
       (f reader)
       (constantly nil)))))

(defn read-file-as-text
  [file]
  (file-reader #(.readAsText %1 file)))

(defn read-file-as-array-buffer
  [file]
  (file-reader #(.readAsArrayBuffer %1 file)))

(defn read-file-as-data-url
  [file]
  (file-reader #(.readAsDataURL ^js %1 file)))

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

(defn write-to-clipboard
  [data]
  (assert (string? data) "`data` should be string")
  (let [cboard (unchecked-get js/navigator "clipboard")]
    (.writeText ^js cboard data)))

(defn read-from-clipboard
  []
  (let [cboard (unchecked-get js/navigator "clipboard")]
    (if (.-readText ^js cboard)
      (rx/from (.readText ^js cboard))
      (throw (ex-info "This browser does not implement read from clipboard protocol"
                      {:not-implemented true})))))

(defn read-image-from-clipboard
  []
  (let [cboard (unchecked-get js/navigator "clipboard")
        read-item (fn [item]
                    (let [img-type (->> (.-types ^js item)
                                        (d/seek #(str/starts-with? % "image/")))]
                      (if img-type
                        (rx/from (.getType ^js item img-type))
                        (rx/empty))))]
    (->> (rx/from (.read ^js cboard)) ;; Get a stream of item lists
         (rx/mapcat identity)     ;; Convert each item into an emission
         (rx/switch-map read-item))))

(defn read-from-paste-event
  [event]
  (let [target (.-target ^js event)]
    (when (and (not (.-isContentEditable target)) ;; ignore when pasting into
               (not= (.-tagName target) "INPUT")) ;; an editable control
      (.. ^js event getBrowserEvent -clipboardData))))

(defn extract-text
  [clipboard-data]
  (when clipboard-data
    (.getData clipboard-data "text")))

(defn extract-images
  [clipboard-data]
  (when clipboard-data
    (let [file-list (-> (.-files ^js clipboard-data))]
      (->> (range (.-length file-list))
           (map #(.item file-list %))
           (filter #(str/starts-with? (.-type %) "image/"))))))

(defn request-fullscreen
  [el]
  (cond
    (obj/in? el "requestFullscreen")
    (.requestFullscreen el)

    (obj/in? el "webkitRequestFullscreen")
    (.webkitRequestFullscreen el)

    :else
    (ex/raise :type :not-supported
              :hint "seems like the current browser does not support fullscreen api.")))

(defn exit-fullscreen
  []
  (cond
    (obj/in? js/document "exitFullscreen")
    (.exitFullscreen js/document)

    (obj/in? js/document "webkitExitFullscreen")
    (.webkitExitFullscreen js/document)

    :else
    (ex/raise :type :not-supported
              :hint "seems like the current browser does not support fullscreen api.")))

(defn observe-resize
  [node]
  (rx/create
   (fn [subs]
     (let [obs (js/ResizeObserver.
                (fn [entries _]
                  (rx/push! subs entries)))]
       (.observe ^js obs node)
       (fn []
         (.disconnect ^js obs))))))
