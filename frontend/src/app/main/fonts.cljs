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
   [app.common.types.text :as txt]
   [app.config :as cf]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.http :as http]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [okulary.core :as l]
   [promesa.core :as p]))

(log/set-level! :info)

(def google-fonts
  (preload-gfonts "fonts/gfonts.2025.05.19.json"))

(def local-fonts
  [{:id "sourcesanspro"
    :name "Source Sans Pro"
    :family "sourcesanspro"
    :variants
    [{:id "200" :name "200" :weight "200" :style "normal" :suffix "extralight" :ttf-url "sourcesanspro-extralight.ttf"}
     {:id "200italic" :name "200 Italic" :weight "200" :style "italic" :suffix "extralightitalic" :ttf-url "sourcesanspro-extralightitalic.ttf"}
     {:id "300" :name "300" :weight "300" :style "normal" :suffix "light" :ttf-url "sourcesanspro-light.ttf"}
     {:id "300italic" :name "300 Italic"  :weight "300" :style "italic" :suffix "lightitalic" :ttf-url "sourcesanspro-lightitalic.ttf"}
     {:id "regular" :name "400" :weight "400" :style "normal" :ttf-url "sourcesanspro-regular.ttf"}
     {:id "italic" :name "400 Italic" :weight "400" :style "italic" :ttf-url "sourcesanspro-italic.ttf"}
     {:id "bold" :name "700" :weight "700" :style "normal" :ttf-url "sourcesanspro-bold.ttf"}
     {:id "bolditalic" :name "700 Italic" :weight "700" :style "italic" :ttf-url "sourcesanspro-bolditalic.ttf"}
     {:id "black" :name "900" :weight "900" :style "normal" :ttf-url "sourcesanspro-black.ttf"}
     {:id "blackitalic" :name "900 Italic" :weight "900" :style "italic" :ttf-url "sourcesanspro-blackitalic.ttf"}]}])

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

(defn find-font-data [data]
  (d/seek
   (fn [font]
     (= (select-keys font (keys data))
        data))
   (vals @fontsdb)))

(defn find-font-family
  "Case insensitive lookup of font-family."
  [family]
  (let [family' (str/lower family)]
    (d/seek
     (fn [{:keys [family]}]
       (= family' (str/lower family)))
     (vals @fontsdb))))

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
       (rx/map :body)
       (rx/catch (fn [err]
                   (.warn js/console "Cannot find the font" (obj/get err "message"))
                   (rx/empty)))))

(defmethod load-font :google
  [{:keys [id ::on-loaded] :as font}]
  (when (exists? js/window)
    (log/info :hint "load-font" :font-id id :backend "google")
    (let [url (generate-gfonts-url font)]
      (->> (fetch-gfont-css url)
           (rx/map process-gfont-css)
           (rx/tap #(on-loaded id))
           (rx/subs! (partial add-font-css! id)))
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
         (get @loading font-id)

         ;; First caller, we create the promise and then wait
         :else
         (let [on-load (fn [resolve]
                         (swap! loaded conj font-id)
                         (swap! loading dissoc font-id)
                         (resolve font-id))

               load-p (-> (p/create
                           (fn [resolve _]
                             (-> font
                                 (assoc ::on-loaded (partial on-load resolve))
                                 (load-font))))
                          ;; We need to wait for the font to be loaded
                          (p/then (partial p/delay 120)))]

           (swap! loading assoc font-id load-p)
           load-p))))))

(defn ready
  [cb]
  (let [fonts (obj/get js/document "fonts")]
    (p/then (obj/get fonts "ready") cb)))

(defn get-default-variant
  [{:keys [variants]}]
  (or (d/seek #(or (= (:id %) "regular")
                   (= (:name %) "regular")) variants)
      (first variants)))

(defn get-variant
  [{:keys [variants] :as font} font-variant-id]
  (or (d/seek #(= (:id %) font-variant-id) variants)
      (get-default-variant font)))

(defn find-variant
  [{:keys [variants] :as font} variant-data]
  (let [props (keys variant-data)]
    (d/seek #(= (select-keys % props) variant-data) variants)))

(defn find-closest-variant
  "Find the closest font weight variant in `font` for `target-weight` with optional `target-style` match.
  When exactly between two weights, choose the higher one."
  [font target-weight target-style]
  (when-let [target-weight (d/parse-integer target-weight)]
    (let [variants (:variants font [])
          result
          (reduce
           (fn [closest-match variant]
             (let [weight (d/parse-integer (:weight variant))
                   distance (abs (- target-weight weight))
                   matches-style? (= target-style (:style variant))
                   current {:variant variant
                            :weight weight
                            :distance distance}]
               (cond
                 ;; Exact match found
                 (and (zero? distance)
                      (if target-style matches-style? true))
                 (reduced current)

                 (nil? closest-match) current

                 ;; Update best match if this variant is closer or equal distance but higher weight
                 (or (< distance (:distance closest-match))
                     (and (= distance (:distance closest-match))
                          (> weight (:weight closest-match))))
                 current

                 ;; Same weight as the `closest-match` but the style matches `target-style`
                 (and (= weight (:weight closest-match)) matches-style?)
                 current

                 :else
                 closest-match)))
           nil
           variants)]
      (:variant result))))

;; Font embedding functions
(defn get-node-fonts
  "Extracts the fonts used by some node"
  [node]
  (let [nodes  (.from js/Array (dom/query-all node "[style*=font]"))
        result (.reduce nodes (fn [obj node]
                                (let [style (.-style node)
                                      font-family (.-fontFamily style)
                                      [_ font] (first
                                                (filter (fn [[_ {:keys [id family]}]]
                                                          (or (= family font-family)
                                                              (= id font-family)))
                                                        @fontsdb))
                                      font-id (:id font)
                                      font-variant (get-variant font (.-fontVariant style))
                                      font-variant-id (:id font-variant)]
                                  (obj/set!
                                   obj
                                   (dm/str font-id ":" font-variant-id)
                                   {:font-id font-id
                                    :font-variant-id font-variant-id})))
                        #js {})]
    (.values js/Object result)))

(defn get-content-fonts
  "Extracts the fonts used by the content of a text shape"
  [content]
  (->> (txt/node-seq content)
       (filter txt/is-text-node?)
       (reduce
        (fn [result {:keys [font-id] :as node}]
          (let [current-font
                (if (some? font-id)
                  (select-keys node [:font-id :font-variant-id])
                  (select-keys txt/default-typography [:font-id :font-variant-id]))]
            (conj result current-font)))
        #{})))

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

(defonce font-styles (js/Map.))

(defn get-font-style-id
  [{:keys [font-id font-variant-id]
    :or   {font-variant-id "regular"}}]
  (dm/fmt "%:%" font-id font-variant-id))

(defn get-font-styles-by-font-ref
  [font-ref]
  (let [id (get-font-style-id font-ref)]
    (if (.has font-styles id)
      (rx/of (.get font-styles id))
      (->> (rx/of font-ref)
           (rx/mapcat fetch-font-css)
           (rx/tap (fn [css] (.set font-styles id css)))))))

(defn render-font-styles-cached
  [font-refs]
  (->> (rx/from font-refs)
       (rx/merge-map get-font-styles-by-font-ref)
       (rx/reduce (fn [acc css] (dm/str acc "\n" css)) "")))
