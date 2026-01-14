;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rasterizer
  "A main entry point for the rasterizer process that is
  executed on a separated iframe."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(log/set-level! :info)

(declare send-success!)
(declare send-failure!)

(defonce data-uri-cache (js/Map.))

(defonce parent-origin
  (dm/str cf/public-uri))

(defn- get-document-element
  [^js svg]
  (.-documentElement svg))

(defn- create-image
  [uri]
  (rx/create
   (fn [subs]
     (let [image (js/Image.)]
       (obj/set! image "onload" #(do
                                   (rx/push! subs image)
                                   (rx/end! subs)))
       (obj/set! image "crossOrigin" "anonymous")
       (obj/set! image "onerror" #(rx/error! subs %))
       (obj/set! image "onabort" #(rx/error! subs (ex/error :type :internal
                                                            :code :abort
                                                            :hint "operation aborted")))
       (obj/set! image "src" uri)
       (fn []
         (obj/set! image "src" "")
         (obj/set! image "onload" nil)
         (obj/set! image "onerror" nil)
         (obj/set! image "onabort" nil))))))

(defn- svg-get-adjusted-size
  "Returns the adjusted size of an SVG."
  [width height max]
  (let [ratio (/ width height)]
    (if (< width height)
      [max (* max (/ 1 ratio))]
      [(* max ratio) max])))

(defn- svg-get-size-from-viewbox
  "Returns the size of an SVG from its viewbox."
  [svg max]
  (let [doc  (get-document-element svg)
        vbox (dom/get-attribute doc "viewBox")]
    (when (string? vbox)
      (let [[_ _ width height] (str/split vbox #"\s+")
            width  (d/parse-integer width 0)
            height (d/parse-integer height 0)]
        (svg-get-adjusted-size width height max)))))

(defn- svg-get-size-from-intrinsic-size
  "Returns the size of an SVG from its intrinsic size."
  [svg max]
  (let [doc    (get-document-element svg)
        width  (dom/get-attribute doc "width")
        height (dom/get-attribute doc "height")
        width  (d/parse-integer width 0)
        height (d/parse-integer height 0)]
    (svg-get-adjusted-size width height max)))

(defn- svg-has-intrinsic-size?
  "Returns true if the SVG has an intrinsic size."
  [svg]
  (let [doc    (get-document-element svg)
        width  (dom/get-attribute doc "width")
        height (dom/get-attribute doc "height")]
    (d/num? width height)))

(defn- svg-get-size
  [svg max]
  (if (svg-has-intrinsic-size? svg)
    (svg-get-size-from-intrinsic-size svg max)
    (svg-get-size-from-viewbox svg max)))

(defn- svg-set-intrinsic-size!
  "Sets the intrinsic size of an SVG to the given max size."
  [^js svg max]
  (let [doc   (get-document-element svg)
        [w h] (svg-get-size svg max)]
    (dom/set-attribute! doc "width" (dm/str w))
    (dom/set-attribute! doc "height" (dm/str h)))
  svg)

(defn- fetch-as-data-uri
  "Fetches a URL as a Data URI."
  [uri]
  (if (.has data-uri-cache uri)
    (let [blob (.get data-uri-cache uri)]
      (rx/from (.text blob)))
    (->> (http/send! {:uri uri
                      :response-type :blob
                      :method :get
                      :mode :cors
                      :omit-default-headers true})
         (rx/catch (fn [cause]
                     (log/error :hint "fetching data uri"
                                :cause cause)
                     (rx/of nil)))
         (rx/mapcat (fn [response]
                      (if (nil? response)
                        (rx/of uri)
                        (->> (rx/of (:body response))
                             (rx/mapcat wapi/read-file-as-data-url)
                             (rx/tap (fn [data-uri] (.set data-uri-cache uri (wapi/create-blob data-uri "text/plain")))))))))))

(defn- svg-update-image!
  "Updates an image in an SVG to a Data URI."
  [image]
  (if-let [href (dom/get-attribute image "href")]
    (if (str/starts-with? href "data:")
      (rx/of image)
      (->> (fetch-as-data-uri href)
           (rx/map (fn [url]
                     (dom/set-attribute! image "href" url)
                     image))))
    (rx/empty)))

(defn- svg-resolve-images!
  "Resolves all images in an SVG to Data URIs."
  [svg]
  (->> (rx/from (dom/query-all svg "image"))
       (rx/mapcat svg-update-image!)
       (rx/ignore)))

(defn- svg-add-style!
  "Adds a <style> node to an SVG."
  [svg styles]
  (let [doc   (get-document-element svg)
        style (dom/create-element svg "http://www.w3.org/2000/svg" "style")]
    (dom/append-child! style (dom/create-text svg styles))
    (dom/append-child! doc style)))

(defn- svg-resolve-external-resources
  "Resolves all external resources in an SVG to Data URIs."
  [styles]
  (->> (rx/from (re-seq #"url\((https?://[^)]+)\)" styles))
       (rx/map second)
       (rx/mapcat (fn [url]
                    (->> (fetch-as-data-uri url)
                         (rx/map (fn [uri] [url uri])))))
       (rx/reduce (fn [styles [url uri]]
                    (str/replace styles url uri))
                  styles)))

(defn- svg-resolve-styles!
  "Resolves all fonts in an SVG to Data URIs."
  [svg styles]
  (->> (svg-resolve-external-resources styles)
       (rx/tap (partial svg-add-style! svg))
       (rx/ignore)))

(defn- svg-resolve-inline-styles!
  "Resolves all inline styles in an SVG to Data URIs."
  [svg]
  (->> (rx/from (dom/query-all svg "[style]"))
       (rx/mapcat (fn [node]
                    (let [styles (dom/get-attribute node "style")]
                      (->> (svg-resolve-external-resources styles)
                           (rx/tap (fn [styles] (dom/set-attribute! node "style" styles)))))))
       (rx/ignore)))

(defn- svg-resolve-style-elements!
  "Resolves all style elements in an SVG to Data URIs."
  [svg]
  (->> (rx/from (dom/query-all svg "style"))
       (rx/mapcat (fn [node]
                    (let [styles (dom/get-text node)]
                      (->> (svg-resolve-external-resources styles)
                           (rx/tap (fn [styles] (dom/set-text! node styles)))))))
       (rx/ignore)))

(defn- svg-resolve-all!
  "Resolves all images and fonts in an SVG to Data URIs."
  [svg styles]
  (rx/concat
   (svg-resolve-images! svg)
   (svg-resolve-styles! svg styles)
   (svg-resolve-inline-styles! svg)
   (svg-resolve-style-elements! svg)
   (rx/of svg)))

(defn- svg-parse
  "Parses an SVG string into an SVG DOM."
  [data]
  (let [parser (js/DOMParser.)]
    (.parseFromString ^js parser data "image/svg+xml")))

(defn- svg-stringify
  "Converts an SVG to a string."
  [svg]
  (let [doc        (get-document-element svg)
        serializer (js/XMLSerializer.)]
    (.serializeToString ^js serializer doc)))

(defn- svg-prepare
  "Prepares an SVG for rendering (resolves images to Data URIs and adds intrinsic size)."
  [data styles width]
  (let [svg (svg-parse data)]
    (->> (svg-resolve-all! svg styles)
         (rx/map #(svg-set-intrinsic-size! % width))
         (rx/map svg-stringify))))

(defn- bitmap->blob
  "Converts an ImageBitmap to a Blob."
  [bitmap options]
  (rx/create
   (fn [subs]
     (let [canvas (dom/create-element "canvas")]
       (set! (.-width ^js canvas)  (.-width ^js bitmap))
       (set! (.-height ^js canvas) (.-height ^js bitmap))
       (let [context (.getContext ^js canvas "bitmaprenderer")]
         (.transferFromImageBitmap ^js context bitmap)
         (.toBlob canvas #(do (rx/push! subs %)
                              (rx/end! subs))
                  options))

       (constantly nil)))))

(defn- render-image-bitmap
  "Renders a thumbnail using it's SVG and returns an ImageBitmap of the image."
  [payload]
  (let [data   (unchecked-get payload "data")
        styles (unchecked-get payload "styles")
        width  (d/nilv (unchecked-get payload "width") 300)
        resize-quality (d/nilv (unchecked-get payload "quality") "medium")]
    (->> (svg-prepare data styles width)
         (rx/map #(wapi/create-blob % "image/svg+xml"))
         (rx/map wapi/create-uri)
         (rx/mapcat (fn [uri]
                      (->> (create-image uri)
                           (rx/mapcat #(wapi/create-image-bitmap-with-workaround % #js {:resizeWidth width
                                                                                        :resizeQuality resize-quality}))
                           (rx/tap #(wapi/revoke-uri uri))))))))

(defn- render-blob
  "Renders a thumbnail using it's SVG and returns a Blob of the image."
  [payload]
  (let [format (unchecked-get payload "format")
        encode-quality (unchecked-get payload "encodeQuality")
        options (cond-> #js {}
                  (string? format) (obj/set! "type" format)
                  (number? encode-quality) (obj/set! "quality" encode-quality))]
    (->> (render-image-bitmap payload)
         (rx/mapcat #(bitmap->blob % options)))))

(defn- render
  "Renders a thumbnail and returns a stream."
  [payload]
  (let [result (d/nilv (unchecked-get payload "result") "blob")]
    (case result
      "image-bitmap" (render-image-bitmap payload)
      (render-blob payload))))

(defn- on-message
  "Handles messages from the main thread."
  [event]
  (let [evdata (unchecked-get event "data")
        evorigin (unchecked-get event "origin")]
    (when (str/starts-with? parent-origin evorigin)
      (let [id      (unchecked-get evdata "id")
            payload (unchecked-get evdata "payload")
            scope   (unchecked-get evdata "scope")]
        (when (and (some? payload)
                   (= scope "penpot/rasterizer"))
          (->> (render payload)
               (rx/subs! (partial send-success! id)
                         (partial send-failure! id))))))))

(defn- listen
  "Initializes the listener for messages from the main thread."
  []
  (.addEventListener js/window "message" on-message))

(defn- send-answer!
  "Sends an answer message."
  [id type payload]
  (let [message #js {:id id
                     :type type
                     :scope "penpot/rasterizer"
                     :payload payload}]
    (when-not (identical? js/window js/parent)
      (if (instance? js/ImageBitmap payload)
        (.postMessage js/parent message parent-origin #js [payload])
        (.postMessage js/parent message parent-origin)))))

(defn- send-success!
  "Sends a success message."
  [id payload]
  (send-answer! id "success" payload))

(defn- send-failure!
  "Sends a failure message."
  [id cause]
  (send-answer! id "failure" (ex-message cause)))

(defn- send-ready!
  "Sends a ready message."
  []
  (send-answer! nil "ready" nil))

(defn ^:export init
  []
  (listen)
  (send-ready!)
  (log/info :hint "initialized"
            :public-uri (dm/str cf/public-uri)
            :parent-uri (dm/str parent-origin)))
