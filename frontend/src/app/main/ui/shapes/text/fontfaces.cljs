;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.text.fontfaces
  (:require
   [app.main.fonts :as fonts]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.embed :as embed]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

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

    (hooks/use-effect-ssr
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
  
(mf/defc fontfaces-style
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["shapes"]))]}
  [props]
  (let [shapes  (obj/get props "shapes")

        content (->> shapes (mapv :content))

        ;; Retrieve the fonts ids used by the text shapes
        fonts (->> content
                   (mapv fonts/get-content-fonts)
                   (reduce set/union #{})
                   (hooks/use-equal-memo))

        ;; Fetch its CSS fontfaces
        fonts-css (use-fonts-css fonts)

        ;; Extract from the CSS the URL's to embed
        fonts-urls (mf/use-memo
                    (mf/deps fonts-css)
                    #(fonts/extract-fontface-urls fonts-css))

        ;; Calculate the data-uris for these fonts
        fonts-embed (embed/use-data-uris fonts-urls)

        ;; Creates a style tag by replacing the urls with the data uri
        style (replace-embeds fonts-css fonts-urls fonts-embed)]
       
    (when (and (some? style) (not (empty? style)))
      [:style style])))
