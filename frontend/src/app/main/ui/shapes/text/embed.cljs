;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.text.embed
  (:refer-clojure :exclude [memoize])
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [app.main.fonts :as fonts]
   [app.util.http :as http]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [app.util.object :as obj]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))


(defonce cache (atom {}))

(defn with-cache
  [{:keys [key max-age]} observable]
  (let [entry (get @cache key)
        age   (when entry
                (dt/diff (dt/now)
                         (:created-at entry)))]
    (if (and (some? entry)
             (< age max-age))
      (rx/of (:data entry))
      (->> observable
           (rx/tap (fn [data]
                     (let [entry {:created-at (dt/now) :data data}]
                       (swap! cache assoc key entry))))))))

(def font-face-template "
/* latin */
@font-face {
  font-family: '%(family)s';
  font-style: %(style)s;
  font-weight: %(weight)s;
  font-display: block;
  src: url(/fonts/%(family)s-%(suffix)s.woff) format('woff');
}
")

;; -- Embed fonts into styles

(defn get-node-fonts
  [node]
  (let [current-font (if (not (nil? (:font-id node)))
                       #{(select-keys node [:font-id :font-variant-id])}
                       #{(select-keys txt/default-text-attrs [:font-id :font-variant-id])})
        children-font (map get-node-fonts (:children node))]
    (reduce set/union (conj children-font current-font))))

(defn get-font-css
  "Given a font and the variant-id, retrieves the style CSS for it."
  [{:keys [id backend family variants] :as font} font-variant-id]
  (if (= :google backend)
    (let [uri (fonts/gfont-url family [{:id font-variant-id}])]
      (->> (http/send! {:method :get
                        :mode :cors
                        :omit-default-headers true
                        :uri uri
                        :response-type :text})
           (rx/map :body)
           (http/as-promise)))
    (let [{:keys [name weight style suffix] :as variant} (d/seek #(= (:id %) font-variant-id) variants)
          result (str/fmt font-face-template {:family family
                                              :style style
                                              :suffix (or suffix font-variant-id)
                                              :weight weight})]
      (p/resolved result))))

(defn- to-promise
  [observable]
  (p/create (fn [resolve reject]
              (->> (rx/take 1 observable)
                   (rx/subs resolve reject)))))

(defn fetch-font-data
  "Parses the CSS and retrieves the font data as DataURI."
  [^string css]
  (let [uris (->> (re-seq #"url\(([^)]+)\)" css)
                  (mapv second))]
    (with-cache {:key uris :max-age (dt/duration {:hours 4})}
      (->> (rx/from (seq uris))
           (rx/mapcat (fn [uri]
                        (->> (http/send! {:method :get :uri uri :response-type :blob :omit-default-headers true})
                             (rx/map :body)
                             (rx/mapcat wapi/read-file-as-data-url)
                             (rx/map #(vector uri %)))))
           (rx/reduce conj [])))))

(defn get-font-data
  "Parses the CSS and retrieves the font data as DataURI."
  [^string css]
  (->> (fetch-font-data css)
       (http/as-promise)))

(defn embed-font
  "Given a font-id and font-variant-id, retrieves the CSS for it and
  convert all external urls to embedded data URI's."
  [{:keys [font-id font-variant-id] :or {font-variant-id "regular"}}]
  (let [{:keys [backend family] :as font} (get @fonts/fontsdb font-id)]
    (p/let [css          (get-font-css font font-variant-id)
            url-to-data  (get-font-data css)
            replace-text (fn [text [url data]] (str/replace text url data))]
      (reduce replace-text css url-to-data))))

;; NOTE: we can't move this to generic hooks namespace because that
;; namespace imports some code incompatible with webworkers and this
;; font embbeding should be able run on browser and webworker
;; contexts.
(defn- memoize
  [val]
  (let [ref (mf/use-ref #js {})]
    (when-not (= (mf/ref-val ref) val)
      (mf/set-ref-val! ref val))
    (mf/ref-val ref)))

(mf/defc embed-fontfaces-style
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["shapes"]))]}
  [props]
  (let [shapes  (obj/get props "shapes")
        node {:children (->> shapes (map :content))}
        fonts (-> node get-node-fonts memoize)
        style (mf/use-state nil)]

    (mf/use-effect
     (mf/deps fonts)
     (fn []
       (-> (p/all (map embed-font fonts))
           (p/then (fn [result]
                     (reset! style (str/join "\n" result)))))))

    (when (some? @style)
      [:style @style])))
