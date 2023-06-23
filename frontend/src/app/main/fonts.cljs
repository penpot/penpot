;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.fonts
  "Fonts management and loading logic."
  (:require-macros [app.main.fonts :refer [preload-gfonts]])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.text :as txt]
   [app.config :as cf]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.http :as http]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [okulary.core :as l]
   [promesa.core :as p]))

(log/set-level! :info)

(def google-fonts
  (preload-gfonts "fonts/gfonts.2022.07.11.json"))

(def local-fonts
  [{:id "sourcesanspro"
    :name "Source Sans Pro"
    :family "sourcesanspro"
    :variants
    [{:id "200" :name "200" :weight "200" :style "normal" :suffix "extralight"}
     {:id "200italic" :name "200 (italic)" :weight "200" :style "italic" :suffix "extralightitalic"}
     {:id "300" :name "300" :weight "300" :style "normal" :suffix "light"}
     {:id "300italic" :name "300 (italic)"  :weight "300" :style "italic" :suffix "lightitalic"}
     {:id "regular" :name "regular" :weight "400" :style "normal"}
     {:id "italic" :name "italic" :weight "400" :style "italic"}
     {:id "bold" :name "bold" :weight "bold" :style "normal"}
     {:id "bolditalic" :name "bold (italic)" :weight "bold" :style "italic"}
     {:id "black" :name "black" :weight "900" :style "normal"}
     {:id "blackitalic" :name "black (italic)" :weight "900" :style "italic"}]}])

(defonce fontsdb (l/atom {}))
(defonce fonts (l/atom []))

(add-watch fontsdb "main"
           (fn [_ _ _ db]
             (->> (vals db)
                  (sort-by :name)
                  (map-indexed #(assoc %2 :index %1))
                  (vec)
                  (reset! fonts))))

(defn register!
  [backend fonts]
  (swap! fontsdb
         (fn [db]
           (let [db    (reduce-kv #(cond-> %1 (= backend (:backend %3)) (dissoc %2)) db db)
                 fonts (map #(assoc % :backend backend) fonts)]
             (merge db (d/index-by :id fonts))))))

(register! :builtin local-fonts)

(when (contains? cf/flags :google-fonts-provider)
  (register! :google google-fonts))

(defn get-font-data [id]
  (get @fontsdb id))

(defn resolve-variants
  [id]
  (get-in @fontsdb [id :variants]))

(defn resolve-fonts
  [backend]
  (get @fonts backend))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FONTS LOADING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:dynamic loaded (l/atom #{}))
(defonce ^:dynamic loading (l/atom {}))

;; NOTE: mainly used on worker, when you don't really need load font
;; only know if the font is needed or not
(defonce ^:dynamic loaded-hints (l/atom #{}))

(defn- add-font-css!
  "Creates a style element and attaches it to the dom."
  [id css]
  (let [node (dom/create-element "style")]
    (dom/set-attribute! node "id" id)
    (dom/set-html! node css)
    (when-let [head (unchecked-get globals/document "head")]
      (dom/append-child! head node))))

;; --- LOADER: BUILTIN

(defmulti ^:private load-font :backend)

(defmethod load-font :default
  [{:keys [backend] :as font}]
  (log/warn :msg "no implementation found for" :backend backend))

(defmethod load-font :builtin
  [{:keys [id ::on-loaded] :as font}]
  (log/debug :hint "load-font" :font-id id :backend "builtin")
  (when (fn? on-loaded)
    (on-loaded id)))

;; --- LOADER: GOOGLE

(defn- generate-gfonts-url
  [{:keys [family variants]}]
  (let [query (dm/str "family=" family ":"
                      (str/join "," (map :id variants))
                      "&display=block")]
    (dm/str
     (-> cf/public-uri
         (assoc :path "/internal/gfonts/css")
         (assoc :query query)))))

(defn- process-gfont-css
  [css]
  (let [base (dm/str (assoc cf/public-uri :path "/internal/gfonts/font"))]
    (str/replace css "https://fonts.gstatic.com/s" base)))

(defn- fetch-gfont-css
  [url]
  (->> (http/send! {:method :get :uri url :mode :cors :response-type :text})
       (rx/map :body)))

(defmethod load-font :google
  [{:keys [id ::on-loaded] :as font}]
  (when (exists? js/window)
    (log/info :hint "load-font" :font-id id :backend "google")
    (let [url (generate-gfonts-url font)]
      (->> (fetch-gfont-css url)
           (rx/map process-gfont-css)
           (rx/tap #(on-loaded id))
           (rx/subs (partial add-font-css! id)))
      nil)))

;; --- LOADER: CUSTOM

(def font-face-template
  "@font-face {
    font-family: '%(family)s';
    font-style: %(style)s;
    font-weight: %(weight)s;
    font-display: block;
    src: url(%(uri)s) format('woff');
  }")

(defn- asset-id->uri
  [asset-id]
  (str (u/join cf/public-uri "assets/by-id/" asset-id)))

(defn generate-custom-font-variant-css
  [family variant]
  (str/fmt font-face-template
           {:family family
            :style (:style variant)
            :weight (:weight variant)
            :uri (asset-id->uri (::woff1-file-id variant))}))

(defn- generate-custom-font-css
  [{:keys [family variants] :as font}]
  (->> variants
       (map #(generate-custom-font-variant-css family %))
       (str/join "\n")))

(defmethod load-font :custom
  [{:keys [id ::on-loaded] :as font}]
  (when (exists? js/window)
    (log/info :hint "load-font" :font-id id :backend "custom")
    (let [css (generate-custom-font-css font)]
      (add-font-css! id css)
      (when (fn? on-loaded)
        (on-loaded)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOAD API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ensure-loaded!
  ([font-id] (ensure-loaded! font-id nil))
  ([font-id variant-id]
   (log/debug :action "try-ensure-loaded!" :font-id font-id :variant-id variant-id)
   (if-not (exists? js/window)
    ;; If we are in the worker environment, we just mark it as loaded
    ;; without really loading it.
    (do
      (swap! loaded-hints conj {:font-id font-id :font-variant-id variant-id})
      (p/resolved font-id))

    (let [font (get @fontsdb font-id)]
      (cond
        (nil? font)
        (p/resolved font-id)

        ;; Font already loaded, we just continue
        (contains? @loaded font-id)
        (p/resolved font-id)

        ;; Font is currently downloading. We attach the caller to the promise
        (contains? @loading font-id)
        (p/resolved (get @loading font-id))

        ;; First caller, we create the promise and then wait
        :else
        (let [on-load (fn [resolve]
                        (swap! loaded conj font-id)
                        (swap! loading dissoc font-id)
                        (resolve font-id))

              load-p (p/create
                      (fn [resolve _]
                        (-> font
                            (assoc ::on-loaded (partial on-load resolve))
                            (load-font))))]

          (swap! loading assoc font-id load-p)
          load-p))))))

(defn ready
  [cb]
  (-> (obj/get-in js/document ["fonts" "ready"])
      (p/then cb)))

(defn get-default-variant
  [{:keys [variants]}]
  (or (d/seek #(or (= (:id %) "regular")
                   (= (:name %) "regular")) variants)
      (first variants)))

(defn get-variant
  [{:keys [variants] :as font} font-variant-id]
  (or (d/seek #(= (:id %) font-variant-id) variants)
      (get-default-variant font)))

;; Font embedding functions

(defn get-content-fonts
  "Extracts the fonts used by the content of a text shape"
  [{font-id :font-id children :children :as content}]
  (let [current-font
        (if (some? font-id)
          #{(select-keys content [:font-id :font-variant-id])}
          #{(select-keys txt/default-text-attrs [:font-id :font-variant-id])})
        children-font (->> children (mapv get-content-fonts))]
    (reduce set/union (conj children-font current-font))))

(defn fetch-font-css
  "Given a font and the variant-id, retrieves the fontface CSS"
  [{:keys [font-id font-variant-id]
    :or   {font-variant-id "regular"}}]
  (let [{:keys [backend family] :as font} (get @fontsdb font-id)]
    (cond
      (nil? font)
      (rx/empty)

      (= :google backend)
      (let [variant (get-variant font font-variant-id)]
        (->> (rx/of (generate-gfonts-url {:family family :variants [variant]}))
             (rx/mapcat fetch-gfont-css)
             (rx/map process-gfont-css)))

      (= :custom backend)
      (let [variant (get-variant font font-variant-id)
            result  (generate-custom-font-variant-css family variant)]
        (rx/of result))

      :else
      (let [{:keys [weight style suffix]} (get-variant font font-variant-id)
            suffix (or suffix font-variant-id)
            params {:uri (dm/str cf/public-uri "fonts/" family "-" suffix ".woff")
                    :family family
                    :style style
                    :weight weight}]
        (rx/of (str/fmt font-face-template params))))))

(defn extract-fontface-urls
  "Parses the CSS and retrieves the font urls"
  [^string css]
  (->> (re-seq #"url\(([^)]+)\)" css)
       (mapv second)))

(defn render-font-styles
  [font-refs]
  (->> (rx/from font-refs)
       (rx/mapcat fetch-font-css)
       (rx/reduce (fn [acc css] (dm/str acc "\n" css)) "")))
