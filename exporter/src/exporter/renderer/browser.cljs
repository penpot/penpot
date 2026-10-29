;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer.browser
  "Renders through a browser page: bitmap, pdf and svg in one namespace.

  Every flow renders one partition of objects per browser checkout:
  navigate once, then capture object by object, calling `on-object`
  with each object carrying its `:path`. `on-object` may return a
  plain value or a promise; it is always awaited.

  `cfg` is task-free infrastructure: the `:exporter.browser/pool`, the
  render `:base-uri` and `:public-uri`, the `:exporter/tmpdir` area and
  the `:svgo?` flag. The
  per-task injections (`on-object`, `check-cancelled`) ride positional. There
  are no promesa chains in this namespace."
  (:require
   ["@penpot/svgo" :as svgo]
   ["xml-js" :as xml]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.types.color :as ctc]
   [app.common.uri :as u]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [exporter.browser :as browser]
   [exporter.renderer.svg-gradient :as svg-gradient]
   [exporter.shell :as shell]
   [exporter.util.mime :as mime]))

(l/set-level! :trace)

(defn- raise-if-cancelled
  "Cooperative checkpoint over the injected zero-arg check-cancelled. This
  backend never blocks indefinitely (every step yields), so
  yielding-point checks are the whole cancel story here. The check-cancelled is
  mandatory: without one it raises `:check-cancelled-missing` instead
  of running unprotected."
  [check-cancelled]
  (if (fn? check-cancelled)
    (check-cancelled)
    (throw (ex/error :type :assertion
                     :code :check-cancelled-missing
                     :hint "browser render needs a zero-arg check-cancelled"))))

(defn- prepare-options
  [uri token scale]
  #js {:screen #js {:width browser/default-viewport-width
                    :height browser/default-viewport-height}
       :viewport #js {:width browser/default-viewport-width
                      :height browser/default-viewport-height}
       :locale "en-US"
       :storageState #js {:cookies (browser/create-cookies uri {:token token})}
       :deviceScaleFactor scale
       :userAgent browser/default-user-agent})

(defn- render-uri
  [base-uri query-params]
  (-> base-uri
      (u/ensure-path-slash)
      (u/join "render.html")
      (assoc :query (u/map->query-string query-params))))

;; --- BITMAP (png, jpeg, webp)

(defn- ^:async render-bitmap-object
  [tmpdir page type object on-object]
  (let [path (await (shell/tempfile tmpdir :prefix "penpot.tmp.bitmap." :suffix (mime/get-extension type)))
        node (browser/select page (str/concat "#screenshot-" (:id object)))]
    (await (browser/wait-for node))
    (case type
      :png  (await (browser/screenshot node {:omit-background? true :type type :path path}))
      :jpeg (await (browser/screenshot node {:omit-background? false :type type :path path}))
      :webp (let [png-path (await (shell/tempfile tmpdir :prefix "penpot.tmp.bitmap." :suffix ".png"))]
              ;; playwright only supports jpg and png, we need to convert it afterwards
              (await (browser/screenshot node {:omit-background? true :type :png :path png-path}))
              (await (shell/run-cmd "convert" png-path "-quality" "100" (str "WEBP:" path)))))
    (await (on-object (assoc object :path path)))))

(defn- ^:async render-bitmap-page
  [tmpdir uri page type objects on-object]
  (l/info :uri uri)
  ;; navigate to the page and perform basic setup
  (await (browser/nav page (str uri)))
  (await (browser/sleep page 1000)) ; the good old fix with sleep
  (await (browser/wait-for-fonts page))
  (await (browser/eval page (js* "() => document.body.style.background = 'transparent'")))
  ;; take the screnshot of requested objects, one by one
  (await (js/Promise.all (mapv (fn [object] (render-bitmap-object tmpdir page type object on-object))
                               objects)))
  nil)

(defn- ^:async render-bitmap
  [cfg {:keys [file-id page-id share-id token scale type objects skip-children]} on-object]
  (let [query {:file-id      file-id
               :page-id      page-id
               :share-id     share-id
               :object-id    (mapv :id objects)
               :route        "objects"
               :skip-children skip-children}
        uri   (render-uri (:base-uri cfg) query)]
    (await (browser/exec (:exporter.browser/pool cfg)
                         (prepare-options uri token scale)
                         (fn [page] (render-bitmap-page (:exporter/tmpdir cfg) uri page type objects on-object))))))

;; --- PDF

(defn- prepare-pdf-uri
  [base-uri {:keys [file-id page-id share-id]} object-id]
  (let [query {:file-id   file-id
               :page-id   page-id
               :share-id  share-id
               :object-id object-id
               :route     "objects"}]
    (-> base-uri
        (u/join "render.html")
        (assoc :query (u/map->query-string query)))))

(defn- sync-page-size
  [dom]
  (browser/eval dom
                (fn [elem]
                  ;; IMPORTANT: No CLJS runtime allowed. Use only JS
                  ;; primitives.  This runs in a context without access to
                  ;; cljs.core. Avoid any functions that transpile to
                  ;; cljs.core/* calls, as they will break in the browser
                  ;; runtime.

                  (let [width      (.getAttribute ^js elem "width")
                        height     (.getAttribute ^js elem "height")
                        style-node (let [node (.createElement js/document "style")]
                                     (.appendChild (.-head js/document) node)
                                     node)]
                    (set! (.-textContent style-node)
                          (dm/str "@page { size: " width "px " height "px; margin: 0; }\n"
                                  "html, body, #app { margin: 0; padding: 0; width: " width "px; height: " height "px; overflow: visible; }"))))))

(defn- ^:async render-pdf-object
  [tmpdir page base-uri params object on-object]
  (let [uri  (prepare-pdf-uri base-uri params (:id object))
        path (await (shell/tempfile tmpdir :prefix "penpot.tmp.pdf." :suffix (mime/get-extension (:type params))))]
    (l/info :uri uri)
    (await (browser/nav page uri))
    (let [dom (browser/select page (dm/str "#screenshot-" (:id object)))]
      (await (browser/wait-for dom))
      (await (sync-page-size dom))
      (await (browser/screenshot dom {:full-page? true}))
      (await (browser/sleep page 2000)) ; the good old fix with sleep
      (await (browser/wait-for-fonts page))
      (await (browser/pdf page {:path path}))
      (await (on-object (assoc object :path path))))))

(defn- ^:async render-pdf-page
  [tmpdir base-uri page params objects on-object check-cancelled]
  (doseq [object objects]
    (raise-if-cancelled check-cancelled)
    (await (render-pdf-object tmpdir page base-uri params object on-object)))
  nil)

(defn- ^:async render-pdf
  [cfg {:keys [token scale] :as params} on-object check-cancelled]
  (let [base-uri (-> (:base-uri cfg)
                     (u/ensure-path-slash))]
    (await (browser/exec (:exporter.browser/pool cfg)
                         (prepare-options base-uri token scale)
                         (fn [page]
                           (render-pdf-page (:exporter/tmpdir cfg) base-uri page params (:objects params) on-object check-cancelled))))))

;; --- SVG

(defn- xml->clj
  [data]
  (js->clj (xml/xml2js data)))

(defn- clj->xml
  [data]
  (xml/js2xml (clj->js data)))

(defn- ^boolean element?
  [item]
  (and (map? item)
       (= "element" (get item "type"))))

(defn- ^boolean foreign-object-element?
  [item]
  (and (element? item)
       (= "foreignObject" (get item "name"))))

(defn- ^boolean empty-defs-element?
  [item]
  (and (= (get item "name") "defs")
       (nil? (get item "attributes"))
       (nil? (get item "elements"))))

(defn- ^boolean empty-path-element?
  [item]
  (and (= (get item "name") "path")
       (let [d (get-in item ["attributes" "d"])]
         (or (str/blank? d)
             (nil? d)
             (str/empty? d)))))

(defn- flatten-toplevel-svg-elements
  "Flattens XML data structure if two nested top-side SVG elements found."
  [item]
  (if (and (= "svg" (get-in item ["elements" 0 "name"]))
           (= "svg" (get-in item ["elements" 0 "elements" 0 "name"])))
    (update-in item ["elements" 0] assoc "elements" (get-in item ["elements" 0 "elements" 0 "elements"]))
    item))

(defn- replace-text-nodes
  "Function responsible of replace the foreignObject elements on the
  provided XML with the previously rasterized PATH's."
  [xmldata nodes]
  (letfn [(replace-fobject [item]
            (if (foreign-object-element? item)
              (let [id   (get-in item ["attributes" "id"])
                    node (get nodes id)]
                (if node
                  (:svgdata node)
                  item))
              item))

          (process-element [item xform]
            (d/update-when item "elements" #(into [] xform %)))]

    (let [xform (comp (remove empty-defs-element?)
                      (remove empty-path-element?)
                      (map replace-fobject))]
      (->> xmldata
           (xml->clj)
           (flatten-toplevel-svg-elements)
           (walk/prewalk (fn [item]
                           (cond-> item
                             (element? item)
                             (process-element xform))))
           (clj->xml)))))

(defn- parse-viewbox
  "Parses viewBox string into width & height map."
  [data]
  (let [[width height] (->> (str/split data #"\s+")
                            (drop 2)
                            (map d/parse-double))]
    {:width width
     :height height}))

(defn- replace-internal-uris
  "Replaces internal-uri references with public-uri in SVG output.
  This ensures that font URLs and other resource references in the
  exported SVG use the public-facing URI accessible to end users."
  [svg-content internal-uri public-uri]
  (let [internal-uri (str internal-uri)
        public-uri   (str public-uri)]
    (if (and (not= internal-uri public-uri)
             (str/includes? svg-content internal-uri))
      (str/replace svg-content internal-uri public-uri)
      svg-content)))

(defn- ^:async convert-to-ppm
  [pngpath]
  (let [ppmpath (str/concat pngpath "origin.ppm")]
    (l/trace :fn :convert-to-ppm :path ppmpath)
    (await (shell/run-cmd "convert" pngpath ppmpath))
    ppmpath))

(defn- ^:async trace-color-mask
  [pbmpath]
  (l/trace :fn :trace-color-mask :pbmpath pbmpath)
  (let [svgpath (str/concat pbmpath ".svg")]
    (await (shell/run-cmd "potrace" "--flat" "-b" "svg" pbmpath "-o" svgpath))
    svgpath))

(defn- ^:async generate-color-layer
  [ppmpath color]
  (when-not (ctc/hex-color-string? color)
    (ex/raise :type :validation
              :code :invalid-color
              :hint (str "invalid hex color: " color)))
  (l/trace :fn :generate-color-layer :ppmpath ppmpath :color color)
  (let [pbmpath (str/concat ppmpath ".mask-" (subs color 1) ".pbm")
        stdout  (await (shell/run-cmd "ppmcolormask" color ppmpath))]
    (await (shell/write-file pbmpath stdout))
    (let [svgpath (await (trace-color-mask pbmpath))
          data    (await (shell/read-file svgpath))
          data    (xml->clj data)
          data    (get-in data ["elements" 1])]
      {:color   color
       :svgdata data})))

(defn- set-path-color
  [id color mapping node]
  (let [color-mapping (get mapping color)]
    (cond
      (and (some? color-mapping)
           (= "transparent" (get color-mapping "type")))
      (update node "attributes" assoc
              "fill" (get color-mapping "hex")
              "fill-opacity" (get color-mapping "opacity"))

      (and (some? color-mapping)
           (= "gradient" (get color-mapping "type")))
      (update node "attributes" assoc
              "fill" (str "url(#gradient-" id "-" (subs color 1) ")"))

      :else
      (update node "attributes" assoc "fill" color))))

(defn- get-gradients
  [id mapping]
  (->> mapping
       (filter (fn [[_color data]]
                 (= (get data "type") "gradient")))
       (mapv (partial svg-gradient/data->gradient-def id))))

(defn- join-color-layers
  [{:keys [id x y width height mapping] :as node} layers]
  (l/trace :fn :join-color-layers :mapping mapping)
  (loop [result (-> (:svgdata (first layers))
                    (assoc "elements" []))
         layers (seq layers)]
    (if-let [{:keys [color svgdata]} (first layers)]
      (recur (->> (get svgdata "elements")
                  (filter #(= (get % "name") "g"))
                  (map (partial set-path-color id color mapping))
                  (update result "elements" into))
             (rest layers))

      ;; Now we have the result containing the svgdata of a
      ;; SVG with all text layers. Now we need to transform
      ;; this SVG to G (Group) and remove unnecessary metadata
      ;; objects.
      (let [vbox      (-> (get-in result ["attributes" "viewBox"])
                          (parse-viewbox))
            transform (str/fmt "translate(%s, %s) scale(%s, %s)" x y
                               (/ width (:width vbox))
                               (/ height (:height vbox)))

            gradient-defs (get-gradients id mapping)

            elements
            (->> (get result "elements")
                 (mapv (fn [group]
                         (let [paths (get group "elements")]
                           (if (= 1 (count paths))
                             (let [path (first paths)]
                               (update path "attributes"
                                       (fn [attrs]
                                         (-> attrs
                                             (d/merge (get group "attributes"))
                                             (update "transform" #(str transform " " %))))))
                             (update-in group ["attributes" "transform"] #(str transform " " %)))))))

            elements (cond->> elements
                       (seq gradient-defs)
                       (into [{"type" "element" "name" "defs" "attributes" {}
                               "elements" gradient-defs}]))]

        (-> result
            (assoc "name" "g")
            (assoc "attributes" {})
            (assoc "elements" elements))))))

(defn- ^:async convert-to-svg
  [ppmpath node]
  (l/trace :fn :convert-to-svg :ppmpath ppmpath :colors (:colors node))
  (let [layers (await (js/Promise.all (mapv (partial generate-color-layer ppmpath)
                                            (:colors node))))]
    (join-color-layers node layers)))

(defn- ^:async trace-node
  [tmpdir node]
  (l/trace :fn :trace-node)
  (let [pngpath (await (shell/tempfile tmpdir :prefix "penpot.tmp.render.svg.parse."
                                       :suffix ".origin.png"))]
    (await (shell/write-file pngpath (:data node)))
    (let [ppmpath (await (convert-to-ppm pngpath))
          svgdata (await (convert-to-svg ppmpath node))]
      (-> node
          (dissoc :data)
          (assoc :svgdata svgdata)))))

(defn- extract-element-attrs
  [^js element]
  (let [^js attrs   (.. element -attributes)
        ^js colors  (.. element -dataset -colors)
        ^js mapping (.. element -dataset -mapping)]
    #js {:id      (.. attrs -id -value)
         :x       (.. attrs -x -value)
         :y       (.. attrs -y -value)
         :width   (.. attrs -width -value)
         :height  (.. attrs -height -value)
         :colors  (.split colors ",")
         :mapping (js/JSON.parse mapping)}))

(defn- ^:async extract-single-node
  [[shot node]]
  (l/trace :fn :extract-single-node)
  (let [attrs (await (browser/eval node extract-element-attrs))]
    {:id      (unchecked-get attrs "id")
     :x       (unchecked-get attrs "x")
     :y       (unchecked-get attrs "y")
     :width   (unchecked-get attrs "width")
     :height  (unchecked-get attrs "height")
     :colors  (vec (unchecked-get attrs "colors"))
     :mapping (js->clj (unchecked-get attrs "mapping"))
     :data    shot}))

(defn- ^:async resolve-text-node
  [page node]
  (let [attrs     (await (browser/eval node extract-element-attrs))
        id        (unchecked-get attrs "id")
        text-node (browser/select page (str "#screenshot-text-" id " foreignObject"))
        shot      (await (browser/screenshot text-node {:omit-background? true :type "png"}))]
    [shot node]))

(defn- ^:async extract-txt-node
  [tmpdir page item]
  (let [[shot node] (await (resolve-text-node page item))
        single      (await (extract-single-node [shot node]))]
    (await (trace-node tmpdir single))))

(defn- ^:async extract-txt-nodes
  [tmpdir page {:keys [id]}]
  (l/trace :fn :process-text-nodes)
  (let [nodes (await (browser/select-all page (str/concat "#screenshot-" id " foreignObject")))
        nodes (await (js/Promise.all (mapv (partial extract-txt-node tmpdir page) nodes)))]
    (d/index-by :id nodes)))

(defn- ^:async extract-svg
  [page {:keys [id]}]
  (let [node (browser/select page (str/concat "#screenshot-" id))]
    (await (browser/wait-for node))
    (await (browser/eval node (fn [elem] (.-outerHTML ^js elem))))))

(defn sanitize-svg-content
  "SVG standard don't allow the entity nbsp. &#160; is equivalent but
  compatible with SVG."
  [content]
  (str/replace content "&nbsp;" "&#160;"))

(defn- ^:async render-svg-object
  [cfg page type object on-object]
  (let [path (await (shell/tempfile (:exporter/tmpdir cfg) :prefix "penpot.tmp.render.svg." :suffix (mime/get-extension type)))
        node (browser/select page (str/concat "#screenshot-" (:id object)))]
    (await (browser/wait-for node))
    (let [xmldata (await (extract-svg page object))
          txtdata (await (extract-txt-nodes (:exporter/tmpdir cfg) page object))
          result  (replace-text-nodes xmldata txtdata)
          result  (sanitize-svg-content result)

          result  (if (:svgo? cfg)
                    (svgo/optimize result svgo/defaultOptions)
                    result)

          result  (replace-internal-uris result (:base-uri cfg) (:public-uri cfg))]
      (await (shell/write-file path result))
      (await (on-object (assoc object :path path))))))

(defn- ^:async render-svg-page
  [cfg uri page type objects on-object]
  (l/info :uri uri)
  ;; navigate to the page and perform basic setup
  (await (browser/nav page (str uri)))
  (await (browser/sleep page 1000)) ; the good old fix with sleep
  (await (browser/wait-for-fonts page))
  ;; take the screnshot of requested objects, one by one
  (await (js/Promise.all (mapv (fn [object] (render-svg-object cfg page type object on-object))
                               objects)))
  nil)

(defn- ^:async render-svg
  [cfg {:keys [file-id page-id share-id token scale type objects]} on-object]
  (let [query {:file-id      file-id
               :page-id      page-id
               :share-id     share-id
               :render-embed true
               :object-id    (mapv :id objects)
               :route        "objects"}
        uri   (render-uri (:base-uri cfg) query)]
    (await (browser/exec (:exporter.browser/pool cfg)
                         (prepare-options uri token scale)
                         (fn [page] (render-svg-page cfg uri page type objects on-object))))))

(defn ^:async render
  "Renders one partition of objects through a browser page: bitmap
  (png, jpeg, webp), pdf or svg depending on the type in `params`.
  Same shape as `exporter.renderer.wasm/render`: `cfg` is task-free
  infrastructure (pools, uris, flags), while `on-object` and `check-cancelled`
  ride positional as the per-task injections — the per-object
  collector and the zero-arg check-cancelled raising `:job-cancelled`.
  The check-cancelled runs here at fail-fast entry plus the sequential pdf
  loop; the concurrent flows rely on the entry check-cancelled and the caller's
  `on-object`. Calls `on-object` with each object carrying its
  `:path`, awaiting it every time, and resolves nil."
  [cfg {:keys [type] :as params} on-object check-cancelled]
  (raise-if-cancelled check-cancelled)
  (case type
    (:png :jpeg :webp) (await (render-bitmap cfg params on-object))
    :pdf (await (render-pdf cfg params on-object check-cancelled))
    :svg (await (render-svg cfg params on-object))
    (ex/raise :type :validation
              :code :unknown-render-type
              :hint (str "unknown render type: " type))))
