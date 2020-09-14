;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.main.ui.shapes.text
  (:require
   [clojure.set :as set]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.data.fetch :as df]
   [app.main.fonts :as fonts]
   [app.main.ui.context :as muc]
   [app.common.data :as d]
   [app.common.geom.shapes :as geom]
   [app.common.geom.matrix :as gmt]
   [app.util.object :as obj]))

;; --- Text Editor Rendering

(defn- generate-root-styles
  [data]
  (let [valign (obj/get data "vertical-align")
        base   #js {:height "100%"
                    :width "100%"
                    :display "flex"}]
    (cond-> base
      (= valign "top") (obj/set! "alignItems" "flex-start")
      (= valign "center") (obj/set! "alignItems" "center")
      (= valign "bottom") (obj/set! "alignItems" "flex-end"))))

(defn- generate-paragraph-styles
  [data]
  (let [base #js {:fontSize "14px"
                  :margin "inherit"
                  :lineHeight "1.2"}
        lh (obj/get data "line-height")
        ta (obj/get data "text-align")]
    (cond-> base
      ta (obj/set! "textAlign" ta)
      lh (obj/set! "lineHeight" lh))))

(defn- generate-text-styles
  [data]
  (let [letter-spacing (obj/get data "letter-spacing")
        text-decoration (obj/get data "text-decoration")
        text-transform (obj/get data "text-transform")

        font-id (obj/get data "font-id")
        font-variant-id (obj/get data "font-variant-id")

        font-family (obj/get data "font-family")
        font-size  (obj/get data "font-size")
        fill (obj/get data "fill")
        opacity (obj/get data "opacity")
        fontsdb (deref fonts/fontsdb)

        base #js {:textDecoration text-decoration
                  :color fill
                  :opacity opacity
                  :textTransform text-transform
                  :lineHeight "inherit"}]

    (when (and (string? letter-spacing)
               (pos? (alength letter-spacing)))
      (obj/set! base "letterSpacing" (str letter-spacing "px")))

    (when (and (string? font-size)
               (pos? (alength font-size)))
      (obj/set! base "fontSize" (str font-size "px")))

    (when (and (string? font-id)
               (pos? (alength font-id)))
      (let [font (get fontsdb font-id)]
        (fonts/ensure-loaded! font-id)
        (let [font-family (or (:family font)
                              (obj/get data "fontFamily"))
              font-variant (d/seek #(= font-variant-id (:id %))
                                   (:variants font))
              font-style  (or (:style font-variant)
                              (obj/get data "fontStyle"))
              font-weight (or (:weight font-variant)
                              (obj/get data "fontWeight"))]
          (obj/set! base "fontFamily" font-family)
          (obj/set! base "fontStyle" font-style)
          (obj/set! base "fontWeight" font-weight))))

    base))

(defn get-all-fonts [node]
  (let [current-font (if (not (nil? (:font-id node)))
                       #{(:font-id node)}
                       #{})
        children-font (map get-all-fonts (:children node))]
    (reduce set/union (conj children-font current-font))))


(defn fetch-font [font-id]
  (let [{:keys [family variants]} (get @fonts/fontsdb font-id)]
    (-> (js/fetch (fonts/gfont-url family variants))
        (p/then (fn [res] (.text res))))))

(defn embed-font [font-id]
  (p/let [font-text (fetch-font font-id)
          url-to-data (->> font-text
                        (re-seq #"url\(([^)]+)\)")
                        (map second)
                        (map df/fetch-as-data-uri)
                        (p/all))]
    (reduce (fn [text [url data]] (str/replace text url data)) font-text url-to-data)))

(mf/defc text-node
  [{:keys [node index] :as props}]
  (let [embed-resources? (mf/use-ctx muc/embed-ctx)
        embeded-fonts    (mf/use-state nil)
        {:keys [type text children]} node]

    (mf/use-effect
     (mf/deps node)
     (fn []
       (when (and embed-resources? (= type "root"))
         (let [font-to-embed (get-all-fonts node)
               embeded (map embed-font font-to-embed)]
           (-> (p/all embeded)
               (p/then (fn [result] (reset! embeded-fonts (str/join "\n" result)))))))))

    (if (string? text)
      (let [style (generate-text-styles (clj->js node))]
        [:span {:style style :key index} text])
      (let [children (map-indexed (fn [index node]
                                    (mf/element text-node {:index index :node node :key index}))
                                  children)]
        (case type
          "root"
          (let [style (generate-root-styles (clj->js node))]

            [:div.root.rich-text
             {:key index
              :style style
              :xmlns "http://www.w3.org/1999/xhtml"}
             (when (not (nil? @embeded-fonts))
               [:style @embeded-fonts])
             children])

          "paragraph-set"
          (let [style #js {:display "inline-block"
                           :width "100%"}]
              [:div.paragraphs {:key index :style style} children])

          "paragraph"
          (let [style (generate-paragraph-styles (clj->js node))]
            [:p {:key index :style style} children])

          nil)))))

(mf/defc text-content
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [root (obj/get props "content")]
    [:& text-node {:index 0 :node root}]))

(defn- retrieve-colors
  [shape]
  (let [colors (into #{} (comp (map :fill)
                               (filter string?))
                     (tree-seq map? :children (:content shape)))]
    (if (empty? colors)
      "#000000"
      (apply str (interpose "," colors)))))

(mf/defc text-shape
  {::mf/wrap-props false}
  [props]
  (let [shape     (unchecked-get props "shape")
        selected? (unchecked-get props "selected?")
        {:keys [id x y width height rotation content]} shape]
    [:foreignObject {:x x
                     :y y
                     :data-colors (retrieve-colors shape)
                     :transform (geom/transform-matrix shape)
                     :id (str id)
                     :width width
                     :height height}
     [:& text-content {:content (:content shape)}]]))

