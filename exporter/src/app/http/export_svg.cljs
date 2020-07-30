(ns app.http.export-svg
  (:require
   [cuerdas.core :as str]
   [clojure.walk :as walk]
   [app.browser :as bwr]
   [app.config :as cfg]
   [lambdaisland.glogi :as log]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as exc :include-macros true]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   ["xml-js" :as xml]
   ["child_process" :as chp]
   ["os" :as os]
   ["path" :as path]
   ["fs" :as fs])
  (:import
   goog.Uri))

(def default-svgo-plugins
  #js [#js {:convertStyleToAttrs false}])

(defn- create-tmpdir!
  [prefix]
  (p/create
   (fn [resolve reject]
     (fs/mkdtemp (path/join (os/tmpdir) prefix)
                 (fn [err dir]
                   (if err
                     (reject err)
                     (resolve dir)))))))

(defn- write-file!
  [fpath content]
  (p/create
   (fn [resolve reject]
     (fs/writeFile fpath content (fn [err]
                                   (if err
                                     (reject err)
                                     (resolve nil)))))))
(defn- read-file
  [fpath]
  (p/create
   (fn [resolve reject]
     (fs/readFile fpath (fn [err content]
                          (if err
                            (reject err)
                            (resolve content)))))))

(defn- run-cmd!
  [cmd]
  (p/create
   (fn [resolve reject]
     (log/info :fn :run-cmd :cmd cmd)
     (chp/exec cmd #js {:encoding "buffer"}
               (fn [error stdout stderr]
                 ;; (log/info :fn :run-cmd :stdout stdout)
                 (if error
                   (reject error)
                   (resolve stdout)))))))

(defn- rmdir!
  [path]
  (p/create
   (fn [resolve reject]
     (fs/rmdir path #js {:recursive true}
               (fn [err]
                 (if err
                   (reject err)
                   (resolve nil)))))))

(defn- parse-xml
  [data]
  (js->clj (xml/xml2js data)))

(defn- encode-xml
  [data]
  (xml/js2xml (clj->js data)))

(defn- render-object
  [browser {:keys [page-id object-id token scale suffix type]}]
  (letfn [(render-in-page [page {:keys [uri cookie] :as rctx}]
            (p/do!
             (bwr/emulate! page {:viewport [1920 1080]
                                 :scale 2})
             (bwr/set-cookie! page cookie)
             (bwr/navigate! page uri)
             (bwr/eval! page (js* "() => document.body.style.background = 'transparent'"))
             page))

          (convert-to-ppm [pngpath]
            (log/info :fn :convert-to-ppm)
            (let [basepath (path/dirname pngpath)
                  ppmpath  (path/join basepath "origin.ppm")]
              (-> (run-cmd! (str "convert " pngpath " " ppmpath))
                  (p/then (constantly ppmpath)))))

          (trace-color-mask [pbmpath]
            (log/info :fn :trace-color-mask :pbmpath pbmpath)
            (let [basepath (path/dirname pbmpath)
                  basename (path/basename pbmpath ".pbm")
                  svgpath  (path/join basepath (str basename ".svg"))]
              (-> (run-cmd! (str "potrace --flat -b svg " pbmpath " -o " svgpath))
                  (p/then (constantly svgpath)))))

          (generate-color-mask [ppmpath color]
            (log/info :fn :generate-color-mask :ppmpath ppmpath :color color)
            (let [basepath (path/dirname ppmpath)
                  pbmpath  (path/join basepath (str "mask-" (subs color 1) ".pbm"))]
              (-> (run-cmd! (str/format "ppmcolormask \"%s\" %s" color ppmpath))
                  (p/then (fn [stdout]
                            (-> (write-file! pbmpath stdout)
                                (p/then (constantly pbmpath)))))
                  (p/then trace-color-mask)
                  (p/then clean-svg)
                  (p/then (fn [svgpath]
                            (p/let [data (read-file svgpath)
                                    data (parse-xml data)
                                    data (get-in data ["elements" 0])]
                              {:svgpath svgpath
                               :color   color
                               :svgdata data}))))))

          (join-color-layers [layers]
            (log/info :fn :join-color-layers :layers (map :svgpath layers))
            (loop [main   (-> (:svgdata (first layers))
                              (assoc "elements" []))
                   layers (seq layers)]
              (if (nil? layers)
                main
                (let [layer    (first layers)
                      elements (map (fn [element]
                                      (update element "attributes" assoc "fill" (:color layer)))
                                    (get-in layer [:svgdata "elements"] []))]
                  (recur (update main "elements" d/concat elements)
                         (next layers))))))

          (convert-to-svg [colors ppmpath]
            (log/info :fn :convert-to-svg :ppmpath ppmpath :colors colors)
            (-> (p/all (map (partial generate-color-mask ppmpath) colors))
                (p/then join-color-layers)))

          (clean-svg [svgpath]
            (log/info :fn :clean-svg :svgpath svgpath)
            (let [basepath (path/dirname svgpath)
                  basename (path/basename svgpath ".svg")
                  svgpath' (path/join basepath (str basename "-optimized.svg"))]
              (-> (run-cmd! (str "svgcleaner " svgpath " " svgpath'))
                  (p/then (constantly svgpath')))))

          (trace-single-node [{:keys [data] :as node}]
            (log/info :fn :trace-single-node)
            (p/let [tdpath  (create-tmpdir! "svgexport-")
                    pngpath (path/join tdpath "origin.png")
                    _       (write-file! pngpath data)
                    ppmpath (convert-to-ppm pngpath)
                    svgdata (convert-to-svg (:colors node) ppmpath)
                    svgdata (update svgdata "attributes" assoc
                                    "width" (:width node)
                                    "height" (:height node)
                                    "x" (:x node)
                                    "y" (:y node))]
              (-> node
                  (dissoc :data)
                  (assoc :tempdir tdpath
                         :svgdata svgdata))))

          (extract-element-attrs [^js element]
            (let [^js attrs  (.. element -attributes)
                  ^js colors (.. element -dataset -colors)]
              #js {:id     (.. attrs -id -value)
                   :x      (.. attrs -x -value)
                   :y      (.. attrs -y -value)
                   :width  (.. attrs -width -value)
                   :height (.. attrs -height -value)
                   :colors (.split colors ",")}))

          (extract-single-node [node]
            (log/info :fn :extract-single-node)

            (p/let [attrs (bwr/eval! node extract-element-attrs)
                    shot  (bwr/screenshot node {:omit-background? true :type "png"})]
              {:id     (unchecked-get attrs "id")
               :x      (unchecked-get attrs "x")
               :y      (unchecked-get attrs "y")
               :width  (unchecked-get attrs "width")
               :height (unchecked-get attrs "height")
               :colors (vec (unchecked-get attrs "colors"))
               :data   shot}))

          (clean-temp-data [{:keys [tempdir] :as node}]
            (p/do!
             (rmdir! tempdir)
             (dissoc node :tempdir)))

          (process-single-text-node [item]
            (-> (p/resolved item)
                (p/then extract-single-node)
                (p/then trace-single-node)
                (p/then clean-temp-data)))

          (process-text-nodes [page]
            (log/info :fn :process-text-nodes)
            (-> (bwr/select-all page "#screenshot foreignObject")
                (p/then #(p/all (map process-single-text-node %)))))

          (replace-nodes-on-main [main nodes]
            (let [main  (parse-xml main)
                  index (d/index-by :id nodes)
                  main  (walk/prewalk (fn [form]
                                        (cond
                                          (and (map? form)
                                               (= "element" (get form "type"))
                                               (= "foreignObject" (get form "name")))
                                          (let [id   (get-in form ["attributes" "id"])
                                                node (get index id)]
                                            (if node
                                              (:svgdata node)
                                              form))

                                          :else
                                          form))
                                      main)]
              (encode-xml main)))

          (render-svg [page]
            (p/let [dom   (bwr/select page "#screenshot")
                    main  (bwr/eval! dom (fn [elem] (.-innerHTML ^js elem)))
                    nodes (process-text-nodes page)]
              (replace-nodes-on-main main nodes)))

          (handle [rctx page]
            (p/let [page (render-in-page page rctx)]
              (render-svg page)))]

    (let [path (str "/render-object/" page-id "/" object-id)
          uri  (doto (Uri. (:public-uri cfg/config))
                 (.setPath "/")
                 (.setFragment path))
          rctx {:cookie {:domain (str (.getDomain uri) ":" (.getPort uri))
                         :key "auth-token"
                         :value token}
                :uri (.toString uri)}]
      (bwr/exec! browser (partial handle rctx)))))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:svg})
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::export-params
  (s/keys :req-un [::name ::suffix ::type ::object-id ::page-id ::scale ::token]
          :opt-un [::filename]))

(defn export
  [browser params]
  (us/assert ::export-params params)
  (p/let [content (render-object browser params)]
    {:content content
     :filename (or (:filename params)
                   (str (str/slug (:name params))
                        (str/trim (:suffix params ""))
                        ".svg"))
     :length (alength content)
     :mime-type "image/svg+xml"}))
