;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer.svg
  (:require
   ["xml-js" :as xml]
   [app.browser :as bw]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(l/set-level! :trace)

(defn- xml->clj
  [data]
  (js->clj (xml/xml2js data)))

(defn- clj->xml
  [data]
  (xml/js2xml (clj->js data)))

(defn ^boolean element?
  [item]
  (and (map? item)
       (= "element" (get item "type"))))

(defn ^boolean group-element?
  [item]
  (and (element? item)
       (= "g" (get item "name"))))

(defn ^boolean shape-element?
  [item]
  (and (element? item)
       (str/starts-with? (get-in item ["attributes" "id"]) "shape-")))

(defn ^boolean foreign-object-element?
  [item]
  (and (element? item)
       (= "foreignObject" (get item "name"))))

(defn ^boolean empty-defs-element?
  [item]
  (and (= (get item "name") "defs")
       (nil? (get item "attributes"))
       (nil? (get item "elements"))))

(defn ^boolean empty-path-element?
  [item]
  (and (= (get item "name") "path")
       (let [d (get-in item ["attributes" "d"])]
         (or (str/blank? d)
             (nil? d)
             (str/empty? d)))))

(defn flatten-toplevel-svg-elements
  "Flattens XML data structure if two nested top-side SVG elements found."
  [item]
  (if (and (= "svg" (get-in item ["elements" 0 "name"]))
           (= "svg" (get-in item ["elements" 0 "elements" 0 "name"])))
    (update-in item ["elements" 0] assoc "elements" (get-in item ["elements" 0 "elements" 0 "elements"]))
    item))

(defn replace-text-nodes
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

(defn parse-viewbox
  "Parses viewBox string into width & height map."
  [data]
  (let [[width height] (->> (str/split data #"\s+")
                            (drop 2)
                            (map d/parse-double))]
    {:width width
     :height height}))

(defn render
  [{:keys [page-id file-id objects token scale type]} on-object]
  (letfn [(convert-to-ppm [pngpath]
            (let [ppmpath (str/concat pngpath "origin.ppm")]
              (l/trace :fn :convert-to-ppm :path ppmpath)
              (-> (sh/run-cmd! (str "convert " pngpath " " ppmpath))
                  (p/then (constantly ppmpath)))))

          (trace-color-mask [pbmpath]
            (l/trace :fn :trace-color-mask :pbmpath pbmpath)
            (let [svgpath (str/concat pbmpath ".svg")]
              (-> (sh/run-cmd! (str "potrace --flat -b svg " pbmpath " -o " svgpath))
                  (p/then (constantly svgpath)))))

          (generate-color-layer [ppmpath color]
            (l/trace :fn :generate-color-layer :ppmpath ppmpath :color color)
            (let [pbmpath (str/concat ppmpath ".mask-" (subs color 1) ".pbm")]
              (-> (sh/run-cmd! (str/format "ppmcolormask \"%s\" %s" color ppmpath))
                  (p/then (fn [stdout]
                            (-> (sh/write-file! pbmpath stdout)
                                (p/then (constantly pbmpath)))))
                  (p/then trace-color-mask)
                  (p/then sh/read-file)
                  (p/then (fn [data]
                            (p/let [data (xml->clj data)
                                    data (get-in data ["elements" 1])]
                              {:color   color
                               :svgdata data}))))))

          (set-path-color [id color mapping node]
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

          (get-stops [data]
            (->> (get-in data ["gradient" "stops"])
                 (mapv (fn [stop-data]
                         {"type" "element"
                          "name" "stop"
                          "attributes" {"offset" (get stop-data "offset")
                                        "stop-color" (get stop-data "color")
                                        "stop-opacity" (get stop-data "opacity")}}))))

          (data->gradient-def [id [color data]]
            (let [id (str "gradient-" id "-" (subs color 1))]
              (if (= type "linear")
                {"type" "element"
                 "name" "linearGradient"
                 "attributes" {"id" id "x1" "0.5" "y1" "1" "x2" "0.5" "y2" "0"}
                 "elements" (get-stops data)}

                {"type" "element"
                 "name" "radialGradient"
                 "attributes" {"id" id "cx" "0.5" "cy" "0.5" "r" "0.5"}
                 "elements" (get-stops data)}
                )))

          (get-gradients [id mapping]
            (->> mapping
                 (filter (fn [[_color data]]
                           (= (get data "type") "gradient")))
                 (mapv (partial data->gradient-def id))))

          (join-color-layers [{:keys [id x y width height mapping] :as node} layers]
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

          (convert-to-svg [ppmpath {:keys [colors] :as node}]
            (l/trace :fn :convert-to-svg :ppmpath ppmpath :colors colors)
            (-> (p/all (map (partial generate-color-layer ppmpath) colors))
                (p/then (partial join-color-layers node))))

          (trace-node [{:keys [data] :as node}]
            (l/trace :fn :trace-node)
            (p/let [pngpath (sh/tempfile :prefix "penpot.tmp.render.svg.parse."
                                         :suffix ".origin.png")
                    _       (sh/write-file! pngpath data)
                    ppmpath (convert-to-ppm pngpath)
                    svgdata (convert-to-svg ppmpath node)]
              (-> node
                  (dissoc :data)
                  (assoc :svgdata svgdata))))

          (extract-element-attrs [^js element]
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

          (extract-single-node [[shot node]]
            (l/trace :fn :extract-single-node)

            (p/let [attrs (bw/eval! node extract-element-attrs)]
              {:id      (unchecked-get attrs "id")
               :x       (unchecked-get attrs "x")
               :y       (unchecked-get attrs "y")
               :width   (unchecked-get attrs "width")
               :height  (unchecked-get attrs "height")
               :colors  (vec (unchecked-get attrs "colors"))
               :mapping (js->clj (unchecked-get attrs "mapping"))
               :data   shot}))

          (resolve-text-node [page node]
            (p/let [attrs (bw/eval! node extract-element-attrs)
                    id (unchecked-get attrs "id")
                    text-node (bw/select page (str "#screenshot-text-" id " foreignObject"))
                    shot (bw/screenshot text-node {:omit-background? true :type "png"})]
              [shot node]))

          (extract-txt-node [page item]
            (-> (p/resolved item)
                (p/then (partial resolve-text-node page))
                (p/then extract-single-node)
                (p/then trace-node)))

          (extract-txt-nodes [page {:keys [id] :as objects}]
            (l/trace :fn :process-text-nodes)
            (-> (bw/select-all page (str/concat "#screenshot-" id " foreignObject"))
                (p/then (fn [nodes] (p/all (map (partial extract-txt-node page) nodes))))
                (p/then (fn [nodes] (d/index-by :id nodes)))))

          (extract-svg [page {:keys [id] :as object}]
            (let [node (bw/select page (str/concat "#screenshot-" id))]
              (bw/wait-for node)
              (bw/eval! node (fn [elem] (.-outerHTML ^js elem)))))

          (prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (render-object [page {:keys [id] :as object}]
            (p/let [path (sh/tempfile :prefix "penpot.tmp.render.svg." :suffix (mime/get-extension type))
                    node (bw/select page (str/concat "#screenshot-" id))]
              (bw/wait-for node)
              (p/let [xmldata (extract-svg page object)
                      txtdata (extract-txt-nodes page object)
                      result  (replace-text-nodes xmldata txtdata)

                      ;; SVG standard don't allow the entity
                      ;; nbsp. &#160; is equivalent but compatible
                      ;; with SVG.
                      result  (str/replace result "&nbsp;" "&#160;")]

                ;; (println "------- ORIGIN:")
                ;; (cljs.pprint/pprint (xml->clj xmldata))
                ;; (println "------- RESULT:")
                ;; (cljs.pprint/pprint (xml->clj result))
                ;; (println "-------")

                (sh/write-file! path result)
                (on-object (assoc object :path path))
                path)))

          (render [uri page]
            (l/info :uri uri)
            (p/do
              ;; navigate to the page and perform basic setup
              (bw/nav! page (str uri))
              (bw/sleep page 1000) ; the good old fix with sleep

              ;; take the screnshot of requested objects, one by one
              (p/run! (partial render-object page) objects)
              nil))]

    (p/let [params {:file-id file-id
                    :page-id page-id
                    :render-embed true
                    :object-id (mapv :id objects)
                    :route "objects"}
            uri    (-> (cf/get :public-uri)
                       (assoc :path "/render.html")
                       (assoc :query (u/map->query-string params)))]
      (bw/exec! (prepare-options uri)
                (partial render uri)))))

