;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.main.ui.shapes.text.embed
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [app.main.data.fetch :as df]
   [app.main.fonts :as fonts]
   [app.util.object :as obj]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.alpha :as mf]))

(def font-face-template "
/* latin */
@font-face {
  font-family: '%(family)s';
  font-style: %(style)s;
  font-weight: %(weight)s;
  font-display: block;
  src: url(/fonts/%(family)s-%(style)s.woff) format('woff');
}
")

;; -- Embed fonts into styles
(defn get-node-fonts
  [node]
  (let [current-font (if (not (nil? (:font-id node)))
                       #{(select-keys node [:font-id :font-variant-id])}
                       #{})
        children-font (map get-node-fonts (:children node))]
    (reduce set/union (conj children-font current-font))))

(defn get-local-font-css
  [font-id font-variant-id]
  (let [{:keys [family variants] :as font}      (get @fonts/fontsdb font-id)
        {:keys [name weight style] :as variant} (d/seek #(= (:id %) font-variant-id) variants)]
    (-> (str/format font-face-template {:family family :style style :width weight})
        (p/resolved))))

(defn get-text-font-data [text]
  (->> text
       (re-seq #"url\(([^)]+)\)")
       (map second)
       (map df/fetch-as-data-uri)
       (p/all)))

(defn embed-font [{:keys [font-id font-variant-id] :or {font-variant-id "regular"}}]
  (let [{:keys [backend]} (get @fonts/fontsdb font-id)]
    (p/let [font-text (case backend
                        :google (fonts/fetch-font font-id font-variant-id)
                        (get-local-font-css font-id font-variant-id))
            url-to-data (get-text-font-data font-text)
            replace-text (fn [text [url data]] (str/replace text url data))]
      (reduce replace-text font-text url-to-data))))

(mf/defc embed-fontfaces-style
  {::mf/wrap-props false}
  [props]
  (let [node  (obj/get props "node")
        style (mf/use-state nil)]
    (mf/use-effect
     (mf/deps node)
     (fn []
       (let [font-to-embed (get-node-fonts node)
             font-to-embed (if (empty? font-to-embed) #{txt/default-text-attrs} font-to-embed)
             embeded       (map embed-font font-to-embed)]
         (-> (p/all embeded)
             (p/then (fn [result] (reset! style (str/join "\n" result))))))))

    (when (some? @style)
      [:style @style])))
