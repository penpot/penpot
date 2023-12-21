;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.text.fontfaces
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.main.fonts :as fonts]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

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
                  (rx/subs!
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
        fonts-css (use-fonts-css fonts)]
    [:style fonts-css]))

(defn shape->fonts
  [shape objects]
  (let [initial (cond-> #{}
                  (cfh/text-shape? shape)
                  (into (fonts/get-content-fonts (:content shape))))]
    (->> (cfh/get-children objects (:id shape))
         (filter cfh/text-shape?)
         (map (comp fonts/get-content-fonts :content))
         (reduce set/union initial))))

(defn shapes->fonts
  [shapes]
  (->> shapes
       (filter cfh/text-shape?)
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
