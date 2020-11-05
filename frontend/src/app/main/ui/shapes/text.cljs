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
   [app.main.ui.shapes.group :refer [mask-id-ctx]]
   [app.common.data :as d]
   [app.common.geom.shapes :as geom]
   [app.common.geom.matrix :as gmt]
   [app.util.object :as obj]
   [app.util.color :as uc]
   [app.util.text :as ut]))

;; --- Text Editor Rendering

(defn- generate-root-styles
  [data]
  (let [valign (obj/get data "vertical-align" "top")
        talign (obj/get data "text-align" "flex-start")
        base   #js {:height "100%"
                    :width "100%"
                    :display "flex"}]
    (cond-> base
      (= valign "top") (obj/set! "alignItems" "flex-start")
      (= valign "center") (obj/set! "alignItems" "center")
      (= valign "bottom") (obj/set! "alignItems" "flex-end")
      (= talign "left") (obj/set! "justifyContent" "flex-start")
      (= talign "center") (obj/set! "justifyContent" "center")
      (= talign "right") (obj/set! "justifyContent" "flex-end")
      (= talign "justify") (obj/set! "justifyContent" "stretch"))))

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
        line-height (obj/get data "line-height")

        font-id (obj/get data "font-id" (:font-id ut/default-text-attrs))
        font-variant-id (obj/get data "font-variant-id")

        font-family (obj/get data "font-family")
        font-size  (obj/get data "font-size")

        ;; Old properties for backwards compatibility
        fill (obj/get data "fill")
        opacity (obj/get data "opacity" 1)

        fill-color (obj/get data "fill-color" fill)
        fill-opacity (obj/get data "fill-opacity" opacity)
        fill-color-gradient (obj/get data "fill-color-gradient" nil)
        fill-color-gradient (when fill-color-gradient
                              (-> (js->clj fill-color-gradient :keywordize-keys true)
                                  (update :type keyword)))

        fill-color-ref-id (obj/get data "fill-color-ref-id")
        fill-color-ref-file (obj/get data "fill-color-ref-file")

        [r g b a] (uc/hex->rgba fill-color fill-opacity)
        background (if fill-color-gradient
                     (uc/gradient->css (js->clj fill-color-gradient))
                     (str/format "rgba(%s, %s, %s, %s)" r g b a))

        fontsdb (deref fonts/fontsdb)

        base #js {:textDecoration text-decoration
                  :textTransform text-transform
                  :lineHeight (or line-height "inherit")
                  "--text-color" background}]

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
                       #{(select-keys node [:font-id :font-variant-id])}
                       #{})
        children-font (map get-all-fonts (:children node))]
    (reduce set/union (conj children-font current-font))))


(defn fetch-font [font-id font-variant-id]
  (let [font-url (fonts/font-url font-id font-variant-id)]
    (-> (js/fetch font-url)
        (p/then (fn [res] (.text res))))))

(defonce font-face-template "
/* latin */
@font-face {
  font-family: '$0';
  font-style: $3;
  font-weight: $2;
  font-display: block;
  src: url(/fonts/%(0)s-$1.woff) format('woff');
}
")

(defn get-local-font-css [font-id font-variant-id]
  (let [{:keys [family variants]} (get @fonts/fontsdb font-id)
        {:keys [name weight style]} (->> variants (filter #(= (:id %) font-variant-id)) first)
        css-str (str/format font-face-template [family name weight style])]
    (p/resolved css-str)))

(defn embed-font [{:keys [font-id font-variant-id] :or {font-variant-id "regular"}}]
  (let [{:keys [backend]} (get @fonts/fontsdb font-id)]
    (p/let [font-text (case backend
                        :google (fetch-font font-id font-variant-id)
                        (get-local-font-css font-id font-variant-id))
            url-to-data (->> font-text
                             (re-seq #"url\(([^)]+)\)")
                             (map second)
                             (map df/fetch-as-data-uri)
                             (p/all))]
      (reduce (fn [text [url data]] (str/replace text url data)) font-text url-to-data))
    ))

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
               font-to-embed (if (empty? font-to-embed) #{ut/default-text-attrs} font-to-embed)
               embeded (map embed-font font-to-embed)]
           (-> (p/all embeded)
               (p/then (fn [result] (reset! embeded-fonts (str/join "\n" result)))))))))

    (if (string? text)
      (let [style (generate-text-styles (clj->js node))]
        [:span.text-node {:style style} (if (= text "") "\u00A0" text)])
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
             [:*
              [:style ".text-node { background: var(--text-color); -webkit-text-fill-color: transparent; -webkit-background-clip: text;"]
              (when (not (nil? @embeded-fonts))
                [:style @embeded-fonts])]
             children])

          "paragraph-set"
          (let [style #js {:display "inline-block"}]
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
        mask-id (mf/use-ctx mask-id-ctx)
        {:keys [id x y width height rotation content]} shape]
    [:foreignObject {:x x
                     :y y
                     :data-colors (retrieve-colors shape)
                     :transform (geom/transform-matrix shape)
                     :id (str id)
                     :width width
                     :height height
                     :mask mask-id}
     [:& text-content {:content (:content shape)}]]))

