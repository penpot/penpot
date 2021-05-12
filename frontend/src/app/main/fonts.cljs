;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.fonts
  "Fonts management and loading logic."
  (:require-macros [app.main.fonts :refer [preload-gfonts]])
  (:require
   [app.config :as cf]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [app.util.logging :as log]
   [lambdaisland.uri :as u]
   [goog.events :as gev]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [promesa.core :as p]))

(log/set-level! :trace)

(def google-fonts
  (preload-gfonts "fonts/gfonts.2020.04.23.json"))

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

(defn- remove-fonts
  [db backend]
  (reduce-kv #(cond-> %1 (= backend (:backend %3)) (dissoc %2)) db db))

(defn register!
  [backend fonts]
  (swap! fontsdb
         (fn [db]
           (let [db    (reduce-kv #(cond-> %1 (= backend (:backend %3)) (dissoc %2)) db db)
                 fonts (map #(assoc % :backend backend) fonts)]
             (merge db (d/index-by :id fonts))))))

(register! :builtin local-fonts)
(register! :google google-fonts)

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

(defonce loaded (l/atom #{}))

(defn- create-link-element
  [uri]
  (let [node (.createElement js/document "link")]
    (unchecked-set node "href" uri)
    (unchecked-set node "rel" "stylesheet")
    (unchecked-set node "type" "text/css")
    node))


(defn- create-style-element
  [css]
  (let [node (.createElement js/document "style")]
    (unchecked-set node "innerHTML" css)
    node))

(defn- load-font-css!
  "Creates a link element and attaches it to the dom for correctly
  load external css resource."
  [url on-loaded]
  (let [node (create-link-element url)
        head (.-head ^js js/document)]
    (gev/listenOnce node "load" (fn [event]
                                  (when (fn? on-loaded)
                                    (on-loaded))))
    (dom/append-child! head node)))

(defn- add-font-css!
  "Creates a style element and attaches it to the dom."
  [css]
  (let [head (.-head ^js js/document)]
    (->> (create-style-element css)
         (dom/append-child! head))))

;; --- LOADER: BUILTIN

(defmulti ^:private load-font :backend)

(defmethod load-font :default
  [{:keys [backend] :as font}]
  (log/warn :msg "no implementation found for" :backend backend))

(defmethod load-font :builtin
  [{:keys [id ::on-loaded] :as font}]
  (log/debug :action "load-font" :font-id id :backend "builtin")
  ;; (js/console.log "[debug:fonts]: loading builtin font" id)
  (when (fn? on-loaded)
    (on-loaded id)))

;; --- LOADER: GOOGLE

(defn generate-gfonts-url
  [{:keys [family variants]}]
  (let [base (str "https://fonts.googleapis.com/css?family=" family)
        variants (str/join "," (map :id variants))]
    (str base ":" variants "&display=block")))

(defmethod load-font :google
  [{:keys [id family variants ::on-loaded] :as font}]
  (when (exists? js/window)
    (log/debug :action "load-font" :font-id id :backend "google")
    (let [url (generate-gfonts-url font)]
      (load-font-css! url (partial on-loaded id))
      nil)))

;; --- LOADER: CUSTOM

(def font-css-template
  "@font-face {
    font-family: '%(family)s';
    font-style: %(style)s;
    font-weight: %(weight)s;
    font-display: block;
    src: url(%(woff2-uri)s) format('woff2'),
         url(%(woff1-uri)s) format('woff'),
         url(%(ttf-uri)s) format('ttf'),
         url(%(otf-uri)s) format('otf');
  }")

(defn- font-id->uri
  [font-id]
  (str (u/join cf/public-uri "assets/by-id/" font-id)))

(defn generate-custom-font-variant-css
  [family variant]
  (str/fmt font-css-template
           {:family family
            :style (:style variant)
            :weight (:weight variant)
            :woff2-uri (font-id->uri (::woff2-file-id variant))
            :woff1-uri (font-id->uri (::woff1-file-id variant))
            :ttf-uri (font-id->uri (::ttf-file-id variant))
            :otf-uri (font-id->uri (::otf-file-id variant))}))

(defn- generate-custom-font-css
  [{:keys [family variants] :as font}]
  (->> variants
       (map #(generate-custom-font-variant-css family %))
       (str/join "\n")))

(defmethod load-font :custom
  [{:keys [id family variants ::on-loaded] :as font}]
  (when (exists? js/window)
    (js/console.log "[debug:fonts]: loading google font" id)
    (let [css (generate-custom-font-css font)]
      (add-font-css! css))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOAD API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ensure-loaded!
  ([id]
   (p/create (fn [resolve]
               (ensure-loaded! id resolve))))
  ([id on-loaded]
   (if (contains? @loaded id)
     (on-loaded id)
     (when-let [font (get @fontsdb id)]
       (load-font (assoc font ::on-loaded on-loaded))
       (swap! loaded conj id)))))

(defn ready
  [cb]
  (-> (obj/get-in js/document ["fonts" "ready"])
      (p/then cb)))

(defn get-default-variant [{:keys [variants]}]
  (or
   (d/seek #(or (= (:id %) "regular") (= (:name %) "regular")) variants)
   (first variants)))
