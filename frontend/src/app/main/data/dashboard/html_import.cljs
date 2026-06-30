;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.dashboard.html-import
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.types.color :as clr]
   [app.common.types.fills :as fills]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.dashboard :as dd]
   [app.main.data.workspace.media :as dwm]
   [app.main.fonts :as fonts]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(def ^:private html-types #{:html-subset})

(def ^:private import-viewport-width 1440)
(def ^:private import-viewport-height 4096)

(defn html-entry?
  [entry]
  (contains? html-types (:type entry)))

(defn css-file?
  [file]
  (str/ends-with? (str/lower (or (.-name ^js file) "")) ".css"))

(defn html-file?
  [file]
  (let [name (str/lower (or (.-name ^js file) ""))]
    (or (str/ends-with? name ".html")
        (str/ends-with? name ".htm"))))

(defn- basename
  [path]
  (some-> path
          (str/split #"[?#]" 2)
          first
          (str/split #"/")
          last
          str/trim))

(defn- strip-extension
  [name]
  (-> name
      (str/replace #"\.[^.]+$" "")
      str/trim))

(defn- px->num
  [value]
  (cond
    (number? value)
    value

    (or (nil? value)
        (= "" value)
        (= "normal" value)
        (= "auto" value))
    0

    :else
    (or (js/parseFloat value) 0)))

(defn- close?
  [a b]
  (< (js/Math.abs (- a b)) 0.5))

(defn- parse-css-color
  [value]
  (let [value (some-> value str/trim str/lower)]
    (cond
      (or (nil? value)
          (= value "")
          (= value "transparent")
          (= value "initial")
          (= value "inherit"))
      nil

      :else
      (let [rgba-match (re-matches #"rgba?\(([^)]+)\)" value)
            parts      (when rgba-match
                         (->> (nth rgba-match 1)
                              (str/split #",")
                              (map str/trim)
                              vec))]
        (cond
          (= 4 (count parts))
          (let [[r g b a] parts
                opacity (or (js/parseFloat a) 1)]
            (when (> opacity 0)
              {:color   (clr/rgb->hex [(js/parseInt r 10)
                                       (js/parseInt g 10)
                                       (js/parseInt b 10)])
               :opacity opacity}))

          (= 3 (count parts))
          (let [[r g b] parts]
            {:color   (clr/rgb->hex [(js/parseInt r 10)
                                     (js/parseInt g 10)
                                     (js/parseInt b 10)])
             :opacity 1})

          :else
          (when-let [color (clr/parse value)]
            {:color color
             :opacity 1}))))))

(defn- rect->map
  [^js rect root-x root-y]
  {:x (- (.-left rect) root-x)
   :y (- (.-top rect) root-y)
   :width (.-width rect)
   :height (.-height rect)})

(defn- visible-element?
  [^js el style]
  (and (pos? (.-width (.getBoundingClientRect el)))
       (pos? (.-height (.getBoundingClientRect el)))
       (not= "none" (.-display style))
       (not= "hidden" (.-visibility style))))

(defn- get-direct-text
  [^js el]
  (->> (array-seq (.-childNodes el))
       (filter #(= (.-nodeType ^js %) js/Node.TEXT_NODE))
       (map #(.-textContent ^js %))
       (apply str)
       str/trim))

(defn- text-content
  [^js el]
  (some-> (.-innerText el) str/trim))

(defn- measure-text-rect
  [^js doc ^js el fallback]
  (try
    (let [range (.createRange doc)]
      (.selectNodeContents range el)
      (let [rect (.getBoundingClientRect range)]
        (if (and (pos? (.-width rect))
                 (pos? (.-height rect)))
          rect
          fallback)))
    (catch :default _
      fallback)))

(defn- content-rect
  [rect style root-x root-y]
  (let [x (+ (.-left rect) (px->num (.-paddingLeft style)))
        y (+ (.-top rect) (px->num (.-paddingTop style)))
        width (max 0 (- (.-width rect)
                        (px->num (.-paddingLeft style))
                        (px->num (.-paddingRight style))))
        height (max 0 (- (.-height rect)
                         (px->num (.-paddingTop style))
                         (px->num (.-paddingBottom style))))]
    {:x (- x root-x)
     :y (- y root-y)
     :width width
     :height height}))

(defn- border-stroke
  [style]
  (let [widths [(px->num (.-borderTopWidth style))
                (px->num (.-borderRightWidth style))
                (px->num (.-borderBottomWidth style))
                (px->num (.-borderLeftWidth style))]
        styles [(.-borderTopStyle style)
                (.-borderRightStyle style)
                (.-borderBottomStyle style)
                (.-borderLeftStyle style)]
        colors [(.-borderTopColor style)
                (.-borderRightColor style)
                (.-borderBottomColor style)
                (.-borderLeftColor style)]
        width  (first widths)
        bstyle (first styles)
        color  (first colors)]
    (when (and (pos? width)
               (every? #(close? width %) widths)
               (every? #(= bstyle %) styles)
               (not= "none" bstyle))
      (when-let [{:keys [color opacity]} (parse-css-color color)]
        [{:stroke-style     (case bstyle
                              "dotted" :dotted
                              "dashed" :dashed
                              :solid)
          :stroke-alignment :inner
          :stroke-width     width
          :stroke-color     color
          :stroke-opacity   opacity}]))))

(defn- frame-fills
  [style]
  (when-let [{:keys [color opacity]} (parse-css-color (.-backgroundColor style))]
    (fills/create {:fill-color color
                   :fill-opacity opacity})))

(defn- corner-radii
  [style]
  {:r1 (px->num (.-borderTopLeftRadius style))
   :r2 (px->num (.-borderTopRightRadius style))
   :r3 (px->num (.-borderBottomRightRadius style))
   :r4 (px->num (.-borderBottomLeftRadius style))})

(defn- visual-box?
  [style]
  (or (some? (frame-fills style))
      (seq (border-stroke style))
      (some pos? (vals (corner-radii style)))
      (< (or (js/parseFloat (.-opacity style)) 1) 1)
      (not= "none" (.-boxShadow style))))

(defn- importable-tag?
  [tag]
  (not (contains? #{"script" "style" "link" "meta" "noscript"} tag)))

(defn- normalize-family
  [family]
  (some-> family
          (str/split #",")
          first
          str/trim
          (str/replace #"^['\"]|['\"]$" "")))

(defn- resolve-font-style
  [style]
  (let [family-name (normalize-family (.-fontFamily style))
        font        (some-> family-name fonts/find-font-family)
        font-style  (or (.-fontStyle style) "normal")
        font-weight (or (.-fontWeight style) "400")
        variant     (or (some-> font (fonts/find-variant {:weight font-weight
                                                          :style font-style}))
                        (some-> font (fonts/find-closest-variant font-weight font-style))
                        (some-> font fonts/get-default-variant))]
    (merge
     {:font-size      (str (max 1 (px->num (.-fontSize style))))
      :font-weight    (str font-weight)
      :font-style     font-style
      :letter-spacing (str (px->num (.-letterSpacing style)))
      :line-height    (let [line-height (px->num (.-lineHeight style))
                            font-size   (max 1 (px->num (.-fontSize style)))]
                        (if (pos? line-height)
                          (str (/ line-height font-size))
                          "1.2"))
      :text-transform (or (.-textTransform style) "none")
      :text-decoration (or (.-textDecorationLine style) "none")
      :text-align     (let [value (or (.-textAlign style) "left")]
                        (if (contains? #{"left" "center" "right" "justify"} value)
                          value
                          "left"))}
     (when-let [{:keys [color opacity]} (parse-css-color (.-color style))]
       {:fills (fills/create {:fill-color color
                              :fill-opacity opacity})})
     (when font
       {:font-id        (:id font)
        :font-family    (:family font)
        :font-variant-id (:id variant)}))))

(declare collect-element)

(defn- collect-children
  [^js doc ^js el root-x root-y]
  (->> (array-seq (.-children el))
       (mapcat #(collect-element doc % root-x root-y))
       vec))

(defn- make-text-desc
  [^js doc ^js el style root-x root-y]
  (let [raw-text (text-content el)]
    (when (seq raw-text)
      (let [rect (measure-text-rect doc el (.getBoundingClientRect el))
            bounds (rect->map rect root-x root-y)]
        [{:id        (uuid/next)
          :node-type :text
          :name      (txt/generate-shape-name raw-text)
          :text      raw-text
          :bounds    bounds
          :style     (resolve-font-style style)}]))))

(defn- make-image-desc
  [^js el root-x root-y]
  (let [src (or (.getAttribute el "src") "")]
    (when (seq src)
      [{:id        (uuid/next)
        :node-type :image
        :name      (or (some-> (.getAttribute el "alt") str/trim not-empty)
                       (basename src)
                       "Image")
        :src       src
        :bounds    (rect->map (.getBoundingClientRect el) root-x root-y)}])))

(defn- make-frame-desc
  [^js doc ^js el style root-x root-y]
  (let [rect      (.getBoundingClientRect el)
        bounds    (rect->map rect root-x root-y)
        children  (collect-children doc el root-x root-y)
        text-desc (when (and (empty? children)
                             (seq (text-content el)))
                    (let [text-rect (measure-text-rect doc el rect)
                          text-bounds (if (pos? (.-width text-rect))
                                        (rect->map text-rect root-x root-y)
                                        (content-rect rect style root-x root-y))]
                      {:id        (uuid/next)
                       :node-type :text
                       :name      (txt/generate-shape-name (text-content el))
                       :text      (text-content el)
                       :bounds    text-bounds
                       :style     (resolve-font-style style)}))
        fills     (or (frame-fills style) [])
        stroke    (or (border-stroke style) [])
        layout?   (contains? #{"flex" "inline-flex"} (.-display style))]
    [{:id        (uuid/next)
      :node-type :frame
      :name      (str/capital (str/lower (.-tagName el)))
      :bounds    bounds
      :fills     fills
      :strokes   stroke
      :opacity   (let [value (js/parseFloat (.-opacity style))]
                   (if (and value (< value 1)) value nil))
      :radii     (corner-radii style)
      :layout    (when layout?
                   {:layout :flex
                    :layout-flex-dir
                    (case (.-flexDirection style)
                      "column" :column
                      "column-reverse" :column-reverse
                      "row-reverse" :row-reverse
                      :row)
                    :layout-wrap-type
                    (if (= "wrap" (.-flexWrap style)) :wrap :nowrap)
                    :layout-gap-type :multiple
                    :layout-gap {:row-gap (px->num (.-rowGap style))
                                 :column-gap (px->num (.-columnGap style))}
                    :layout-padding-type :multiple
                    :layout-padding {:p1 (px->num (.-paddingTop style))
                                     :p2 (px->num (.-paddingRight style))
                                     :p3 (px->num (.-paddingBottom style))
                                     :p4 (px->num (.-paddingLeft style))}
                    :layout-align-items
                    (case (.-alignItems style)
                      "flex-end" :end
                      "center" :center
                      "stretch" :stretch
                      :start)
                    :layout-justify-content
                    (case (.-justifyContent style)
                      "center" :center
                      "flex-end" :end
                      "space-between" :space-between
                      "space-around" :space-around
                      "space-evenly" :space-evenly
                      :start)})
      :children  (cond-> children text-desc (conj text-desc))}]))

(defn- collect-element
  [^js doc ^js el root-x root-y]
  (let [tag   (str/lower (.-tagName el))
        style (.getComputedStyle (.-defaultView doc) el)
        has-elements? (pos? (.-length (.-children el)))
        own-text?     (seq (get-direct-text el))
        display       (.-display style)
        visual?       (visual-box? style)]
    (cond
      (not (importable-tag? tag))
      []

      (not (visible-element? el style))
      []

      (= display "contents")
      (collect-children doc el root-x root-y)

      (= tag "img")
      (or (make-image-desc el root-x root-y) [])

      (and (not has-elements?) own-text? (not visual?))
      (or (make-text-desc doc el style root-x root-y) [])

      (or has-elements? visual? own-text?)
      (make-frame-desc doc el style root-x root-y)

      :else
      [])))

(defn- extract-linked-css
  [html]
  (let [doc (.parseFromString (js/DOMParser.) html "text/html")]
    (->> (.querySelectorAll doc "link[rel='stylesheet']")
         array-seq
         (map #(.getAttribute ^js % "href"))
         (keep basename)
         set)))

(defn- read-css-files
  [html css-files]
  (let [linked    (extract-linked-css html)
        css-files (if (seq linked)
                    (filter #(contains? linked (:name %)) css-files)
                    (if (= 1 (count css-files)) css-files []))]
    (if (seq css-files)
      (->> (rx/from css-files)
           (rx/mapcat #(rx/from (.text ^js (:file %))))
           (rx/reduce conj []))
      (rx/of []))))

(defn- inject-styles
  [html css-texts]
  (let [styles (apply str (map #(str "<style>\n" % "\n</style>") css-texts))]
    (cond
      (str/includes? (str/lower html) "</head>")
      (str/replace html #"(?i)</head>" (str styles "</head>"))

      (str/includes? (str/lower html) "<body")
      (str/replace html #"(?i)<body([^>]*)>" (str "<body$1>" styles))

      :else
      (str "<!doctype html><html><head>" styles "</head><body>" html "</body></html>"))))

(defn- union-rect
  [rects]
  (reduce
   (fn [acc {:keys [x y width height]}]
     (let [right  (+ x width)
           bottom (+ y height)]
       (if (nil? acc)
         {:x x :y y :right right :bottom bottom}
         {:x (min (:x acc) x)
          :y (min (:y acc) y)
          :right (max (:right acc) right)
          :bottom (max (:bottom acc) bottom)})))
   nil
   rects))

(defn- wait-two-frames
  [cb]
  (js/requestAnimationFrame
   (fn []
     (js/requestAnimationFrame cb))))

(defn- measure-layout
  [html css-texts entry-name]
  (js/Promise.
   (fn [resolve reject]
     (let [iframe (.createElement js/document "iframe")
           cleanup #(when (.-parentNode iframe)
                      (.removeChild (.-parentNode iframe) iframe))]
       (set! (.-style.position iframe) "fixed")
       (set! (.-style.left iframe) "-20000px")
       (set! (.-style.top iframe) "0")
       (set! (.-style.width iframe) (str import-viewport-width "px"))
       (set! (.-style.height iframe) (str import-viewport-height "px"))
       (set! (.-style.opacity iframe) "0")
       (set! (.-style.pointerEvents iframe) "none")
       (set! (.-sandbox iframe) "allow-same-origin")
       (set! (.-onload iframe)
             (fn []
               (wait-two-frames
                (fn []
                  (try
                    (let [doc        (.-contentDocument iframe)
                          body       (.-body doc)
                          single-el  (when (= 1 (.-length (.-children body)))
                                       (aget (.-children body) 0))
                          single-style (when single-el
                                         (.getComputedStyle (.-defaultView doc) single-el))
                          root-el    (if (and single-el
                                              (not= "img" (str/lower (.-tagName single-el)))
                                              (or (pos? (.-length (.-children single-el)))
                                                  (visual-box? single-style)))
                                       single-el
                                       body)
                          root-rect  (.getBoundingClientRect root-el)
                          root-x     (.-left root-rect)
                          root-y     (.-top root-rect)
                          root-style (.getComputedStyle (.-defaultView doc) root-el)
                          children   (let [children (collect-children doc root-el root-x root-y)]
                                       (if (and (empty? children)
                                                (seq (text-content root-el)))
                                         (let [text-rect (measure-text-rect doc root-el root-rect)
                                               text-bounds (if (pos? (.-width text-rect))
                                                             (rect->map text-rect root-x root-y)
                                                             (content-rect root-rect root-style root-x root-y))]
                                           [{:id        (uuid/next)
                                             :node-type :text
                                             :name      (txt/generate-shape-name (text-content root-el))
                                             :text      (text-content root-el)
                                             :bounds    text-bounds
                                             :style     (resolve-font-style root-style)}])
                                         children))
                          child-rects (map :bounds children)
                          union      (union-rect child-rects)
                          bounds     {:x 0
                                      :y 0
                                      :width (max (or (some-> union :right) 0)
                                                  (.-width root-rect))
                                      :height (max (or (some-> union :bottom) 0)
                                                   (.-height root-rect))}
                          board      {:id        (uuid/next)
                                      :node-type :frame
                                      :name      (or (strip-extension entry-name) "Imported HTML")
                                      :bounds    bounds
                                      :fills     (or (frame-fills root-style) [])
                                      :strokes   (or (border-stroke root-style) [])
                                      :opacity   (let [value (js/parseFloat (.-opacity root-style))]
                                                   (if (and value (< value 1)) value nil))
                                      :radii     (corner-radii root-style)
                                      :children  children}]
                      (cleanup)
                      (resolve board))
                    (catch :default cause
                      (cleanup)
                      (reject cause)))))))
       (.appendChild (.-body js/document) iframe)
       (set! (.-srcdoc iframe) (inject-styles html css-texts))))))

(defn analyze-entry
  [entry]
  (let [file-id (or (:file-id entry) (uuid/next))]
    (->> (rx/from (.text ^js (:file entry)))
         (rx/map
          (fn [content]
            (let [doc (.parseFromString (js/DOMParser.) content "text/html")
                  parser-error (.querySelector doc "parsererror")]
              (if parser-error
                {:file-id file-id
                 :name (:name entry)
                 :uri (:uri entry)
                 :type :html-subset
                 :status :error
                 :error "Invalid HTML document"}
                {:file-id file-id
                 :name (:name entry)
                 :uri (:uri entry)
                 :file (:file entry)
                 :type :html-subset
                 :status :success
                 :content content}))))
         (rx/catch
          (fn [_]
            (rx/of {:file-id file-id
                    :name (:name entry)
                    :uri (:uri entry)
                    :type :html-subset
                    :status :error
                    :error "Unable to read HTML file"}))))))

(defn- image-descriptors
  [desc]
  (->> (tree-seq #(seq (:children %)) :children desc)
       (filter #(= :image (:node-type %)))))

(defn- upload-image
  [file-id {:keys [src name]}]
  (cond
    (str/starts-with? src "data:")
    (rp/cmd! :upload-file-media-object
             {:file-id file-id
              :name (or name "Image")
              :is-local true
              :content (wapi/data-uri->blob src)})

    (or (str/starts-with? src "http://")
        (str/starts-with? src "https://"))
    (dwm/upload-media-url (or name "Image") file-id src)

    :else
    (rx/of nil)))

(defn- resolve-image-map
  [file-id desc]
  (let [images (image-descriptors desc)]
    (if (seq images)
      (->> (rx/from images)
           (rx/mapcat
            (fn [image]
              (->> (upload-image file-id image)
                   (rx/map (fn [media] [(:id image) media])))))
           (rx/reduce
            (fn [acc [id media]]
              (if (some? media)
                (assoc acc id media)
                acc))
            {}))
      (rx/of {}))))

(defn- desc->shape
  [desc parent-id frame-id image-map]
  (let [{:keys [node-type name bounds fills strokes opacity radii text style]} desc
        {:keys [x y width height]} bounds]
    (case node-type
      :frame
      (cts/setup-shape
       (cond-> {:id (:id desc)
                :type :frame
                :name name
                :x x
                :y y
                :width (max width 1)
                :height (max height 1)
                :parent-id parent-id
                :frame-id frame-id
                :fills (vec fills)
                :strokes (vec strokes)
                :shapes []}
         opacity (assoc :opacity opacity)
         radii (merge radii)
         (:layout desc) (merge (:layout desc))))

      :image
      (when-let [media (get image-map (:id desc))]
        (cts/setup-shape
         {:id (:id desc)
          :type :rect
          :name name
          :x x
          :y y
          :width (max width 1)
          :height (max height 1)
          :parent-id parent-id
          :frame-id frame-id
          :fills [{:fill-opacity 1
                   :fill-image {:id (:id media)
                                :width (:width media)
                                :height (:height media)
                                :mtype (:mtype media)
                                :name (:name media)
                                :keep-aspect-ratio true}}]
          :strokes []}))

      :text
      (-> (cts/setup-shape
           {:id (:id desc)
            :type :text
            :name name
            :x x
            :y y
            :width (max width 1)
            :height (max height 1)
            :parent-id parent-id
            :frame-id frame-id
            :grow-type :fixed})
          (update :content #(apply txt/change-text % text (mapcat identity style)))
          (dissoc :position-data))

      nil)))

(defn- add-desc-tree
  [changes objects desc parent-id frame-id image-map]
  (if-let [shape (desc->shape desc parent-id frame-id image-map)]
    (let [[shape' add-changes]
          (cfsh/prepare-add-shape changes shape objects)
          objects' (cond-> (assoc objects (:id shape') shape')
                     (some? parent-id)
                     (update-in [parent-id :shapes] (fnil conj []) (:id shape')))
          next-frame-id (if (= :frame (:type shape')) (:id shape') frame-id)]
      (reduce
       (fn [[changes* objects*] child]
         (add-desc-tree changes* objects* child (:id shape') next-frame-id image-map))
       [add-changes objects']
       (:children desc)))
    [changes objects]))

(defn- build-changes
  [file board image-map]
  (let [page-id  (first (get-in file [:data :pages]))
        page     (get-in file [:data :pages-index page-id])
        objects  (:objects page)
        changes  (-> (pcb/empty-changes nil page-id)
                     (pcb/with-page page)
                     (pcb/with-objects objects))
        [changes _]
        (add-desc-tree changes objects board uuid/zero uuid/zero image-map)]
    changes))

(defn import-entry
  [project-id css-files entry]
  (let [session-id (:session-id @st/state)
        html (:content entry)
        name (or (strip-extension (:name entry)) (:name entry))]
    (rx/concat
     (rx/of {:file-id (:file-id entry)
             :status :progress
             :progress {:type :process-page
                        :file (:name entry)}})
     (->> (read-css-files html css-files)
          (rx/mapcat
           (fn [css-texts]
             (->> (rp/cmd! :create-file {:project-id project-id
                                         :name name})
                  (rx/tap #(st/emit! (dd/file-created %)))
                  (rx/mapcat
                   (fn [file]
                     (->> (rx/from (measure-layout html css-texts (:name entry)))
                          (rx/mapcat
                           (fn [board]
                             (->> (resolve-image-map (:id file) board)
                                  (rx/mapcat
                                   (fn [image-map]
                                     (let [changes (build-changes file board image-map)
                                           params  {:id (:id file)
                                                    :revn (:revn file)
                                                    :vern (:vern file)
                                                    :session-id session-id
                                                    :changes (:redo-changes changes)
                                                    :features (:features file)}]
                                       (rp/cmd! :update-file params))))))
                          (rx/map
                           (fn [_]
                             {:file-id (:file-id entry)
                              :status :finish
                              :imported-file-id (:id file)})))))
                  (rx/catch
                   (fn [cause]
                     (rx/of {:file-id (:file-id entry)
                             :status :error
                             :error (or (ex-message cause)
                                        "Unable to import HTML file")})))))))))))
