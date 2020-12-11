;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.webapi
  "HTML5 web api helpers."
  (:require
   [app.common.exceptions :as ex]
   [app.util.object :as obj]
   [promesa.core :as p]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.util.transit :as t]))

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
      (-> ^js event
          (.getBrowserEvent)
          (.-clipboardData)))))

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
              :hint "seems like the current browset does not support fullscreen api.")))

(defn exit-fullscreen
  []
  (cond
    (obj/in? js/document "exitFullscreen")
    (.exitFullscreen js/document)

    (obj/in? js/document "webkitExitFullscreen")
    (.webkitExitFullscreen js/document)

    :else
    (ex/raise :type :not-supported
              :hint "seems like the current browset does not support fullscreen api.")))
