;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.renderer.svg
  (:require
   ["path" :as path]
   ["xml-js" :as xml]
   [app.browser :as bw]
   [app.common.data :as d]
   [app.common.exceptions :as ex :include-macros true]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [lambdaisland.glogi :as log]
   [lambdaisland.uri :as u]
   [app.renderer.bitmap :refer [create-cookie]]
   [promesa.core :as p]))

(log/set-level "app.renderer.svg" :trace)

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


(defn- render-object
  [{:keys [page-id file-id object-id token scale suffix type]}]
  (letfn [(convert-to-ppm [pngpath]
            (log/trace :fn :convert-to-ppm)
            (let [basepath (path/dirname pngpath)
                  ppmpath  (path/join basepath "origin.ppm")]
              (-> (sh/run-cmd! (str "convert " pngpath " " ppmpath))
                  (p/then (constantly ppmpath)))))

          (trace-color-mask [pbmpath]
            (log/trace :fn :trace-color-mask :pbmpath pbmpath)
            (let [basepath (path/dirname pbmpath)
                  basename (path/basename pbmpath ".pbm")
                  svgpath  (path/join basepath (str basename ".svg"))]
              (-> (sh/run-cmd! (str "potrace --flat -b svg " pbmpath " -o " svgpath))
                  (p/then (constantly svgpath)))))
          
          (generate-color-layer [ppmpath color]
            (log/trace :fn :generate-color-layer :ppmpath ppmpath :color color)
            (let [basepath (path/dirname ppmpath)
                  pbmpath  (path/join basepath (str "mask-" (subs color 1) ".pbm"))]
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
                 (filter (fn [[color data]]
                           (= (get data "type") "gradient")))
                 (mapv (partial data->gradient-def id))))

          (join-color-layers [{:keys [id x y width height mapping] :as node} layers]
            (log/trace :fn :join-color-layers :mapping mapping)
            (loop [result (-> (:svgdata (first layers))
                              (assoc "elements" []))
                   layers (seq layers)]
              (if-let [{:keys [color svgdata]} (first layers)]
                (recur (->> (get svgdata "elements")
                            (filter #(= (get % "name") "g"))
                            (map (partial set-path-color id color mapping))
                            (update result "elements" d/concat))
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
                                 (not (empty? gradient-defs))
                                 (d/concat [{"type" "element" "name" "defs" "attributes" {}
                                             "elements" gradient-defs}]))]

                  (-> result
                      (assoc "name" "g")
                      (assoc "attributes" {})
                      (assoc "elements" elements))))))

          (convert-to-svg [ppmpath {:keys [colors] :as node}]
            (log/trace :fn :convert-to-svg :ppmpath ppmpath :colors colors)
            (-> (p/all (map (partial generate-color-layer ppmpath) colors))
                (p/then (partial join-color-layers node))))

          (trace-node [{:keys [data] :as node}]
            (log/trace :fn :trace-node)
            (p/let [tdpath  (sh/create-tmpdir! "svgexport-")
                    pngpath (path/join tdpath "origin.png")
                    _       (sh/write-file! pngpath data)
                    ppmpath (convert-to-ppm pngpath)
                    svgdata (convert-to-svg ppmpath node)]
              (-> node
                  (dissoc :data)
                  (assoc :tempdir tdpath
                         :svgdata svgdata))))

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
            (log/trace :fn :extract-single-node)

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

          (clean-temp-data [{:keys [tempdir] :as node}]
            (p/do!
             (sh/rmdir! tempdir)
             (dissoc node :tempdir)))

          (process-text-node [page item]
            (-> (p/resolved item)
                (p/then (partial resolve-text-node page))
                (p/then extract-single-node)
                (p/then trace-node)
                (p/then clean-temp-data)))

          (process-text-nodes [page]
            (log/trace :fn :process-text-nodes)
            (-> (bw/select-all page "#screenshot foreignObject")
                (p/then (fn [nodes] (p/all (map (partial process-text-node page) nodes))))))

          (extract-svg [page]
            (p/let [dom     (bw/select page "#screenshot")
                    xmldata (bw/eval! dom (fn [elem] (.-outerHTML ^js elem)))
                    nodes   (process-text-nodes page)
                    nodes   (d/index-by :id nodes)
                    result  (replace-text-nodes xmldata nodes)]
              ;; (println "------- ORIGIN:")
              ;; (cljs.pprint/pprint (xml->clj xmldata))
              ;; (println "------- RESULT:")
              ;; (cljs.pprint/pprint (xml->clj result))
              ;; (println "-------")
              result))

          (render-in-page [page {:keys [uri cookie] :as rctx}]
            (let [viewport {:width 1920
                            :height 1080
                            :scale 4}
                  options  {:viewport viewport
                            :timeout 15000
                            :cookie cookie}]
              (p/do!
               (bw/configure-page! page options)
               (bw/navigate! page uri)
               (bw/wait-for page "#screenshot")
               (bw/sleep page 2000)
               ;; (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))
               page)))

          (handle [rctx page]
            (p/let [page (render-in-page page rctx)]
              (extract-svg page)))]

    (let [path   (str "/render-object/" file-id "/" page-id "/" object-id "?render-texts=true")
          uri    (-> (u/uri (cf/get :public-uri))
                     (assoc :path "/")
                     (assoc :fragment path))
          cookie (create-cookie uri token)
          rctx   {:cookie cookie
                  :uri (str uri)}]
      (log/info :uri (:uri rctx))
      (bw/exec! (partial handle rctx)))))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:svg})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::render-params
  (s/keys :req-un [::name ::suffix ::type ::object-id ::page-id ::file-id ::scale ::token]
          :opt-un [::filename]))

(defn render
  [params]
  (us/assert ::render-params params)
  (p/let [content (render-object params)]
    {:content content
     :filename (or (:filename params)
                   (str (:name params)
                        (:suffix params "")
                        ".svg"))
     :length (alength content)
     :mime-type "image/svg+xml"}))

