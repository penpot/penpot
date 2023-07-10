;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.text.fontfaces
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.main.fonts :as fonts]
   [app.main.ui.shapes.embed :as embed]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn replace-embeds
  "Replace into the font-faces of a CSS the URL's that are present in `embed-data` by its
  data-uri"
  [css urls embed-data]
  (letfn [(replace-url [css url]
            (str/replace css url (get embed-data url url)))]
    (->> urls
         (reduce replace-url css))))

(defn use-fonts-css
  "Hook that retrieves the CSS of the fonts passed as parameter"
  [fonts]
  (let [fonts-css-ref (mf/use-ref "")
        redraw (mf/use-state 0)]

    (mf/use-ssr-effect
     (mf/deps fonts)
     (fn []
       (let [sub
             (->> (rx/from fonts)
                  (rx/merge-map fonts/fetch-font-css)
                  (rx/reduce conj [])
                  (rx/subs
                   (fn [result]
                     (let [css (str/join "\n" result)]
                       (when-not (= (mf/ref-val fonts-css-ref) css)
                         (mf/set-ref-val! fonts-css-ref css)
                         (reset! redraw inc))))))]
         #(rx/dispose! sub))))

    (mf/ref-val fonts-css-ref)))

(mf/defc fontfaces-style-html
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["fonts"]))]}
  [props]

  (let [fonts (obj/get props "fonts")

        ;; Fetch its CSS fontfaces
        fonts-css (use-fonts-css fonts)]

    [:style fonts-css]))

(mf/defc fontfaces-style-render
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["fonts"]))]}
  [props]

  (let [fonts (obj/get props "fonts")

        ;; Fetch its CSS fontfaces
        fonts-css (use-fonts-css fonts)

        ;; Extract from the CSS the URL's to embed
        fonts-urls (mf/use-memo
                    (mf/deps fonts-css)
                    #(fonts/extract-fontface-urls fonts-css))

        ;; Calculate the data-uris for these fonts
        fonts-embed (embed/use-data-uris fonts-urls)

        loading? (some? (d/seek #(not (contains? fonts-embed %)) fonts-urls))

        ;; Creates a style tag by replacing the urls with the data uri
        style (replace-embeds fonts-css fonts-urls fonts-embed)]

    (cond
      (d/not-empty? style)
      [:style {:data-loading loading?} style]

      (d/not-empty? fonts)
      [:style {:data-loading true}])))

(defn shape->fonts
  [shape objects]
  (let [initial (cond-> #{}
                  (cph/text-shape? shape)
                  (into (fonts/get-content-fonts (:content shape))))]
    (->> (cph/get-children objects (:id shape))
         (filter cph/text-shape?)
         (map (comp fonts/get-content-fonts :content))
         (reduce set/union initial))))

(defn shapes->fonts
  [shapes]
  (->> shapes
       (filter cph/text-shape?)
       (map (comp fonts/get-content-fonts :content))
       (reduce set/union #{})))

(mf/defc fontfaces-style
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["fonts"]))]}
  [props]
  (let [;; Retrieve the fonts ids used by the text shapes
        fonts (obj/get props "fonts")]
    (when (d/not-empty? fonts)
      [:> fontfaces-style-render {:fonts fonts}])))
