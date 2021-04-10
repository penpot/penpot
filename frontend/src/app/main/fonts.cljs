;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.fonts
  "Fonts management and loading logic."
  (:require-macros [app.main.fonts :refer [preload-gfonts]])
  (:require
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [promesa.core :as p]))

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
(defonce fontsview (l/atom {}))

(defn- materialize-fontsview
  [db]
  (reset! fontsview (reduce-kv (fn [acc k v]
                                 (assoc acc k (sort-by :name v)))
                               {}
                               (group-by :backend (vals db)))))
(add-watch fontsdb "main"
           (fn [_ _ _ db]
             (ts/schedule #(materialize-fontsview db))))

(defn register!
  [backend fonts]
  (let [fonts (map #(assoc % :backend backend) fonts)]
    (swap! fontsdb #(merge % (d/index-by :id fonts)))))

(register! :builtin local-fonts)
(register! :google google-fonts)

(defn get-font-data [id]
  (get @fontsdb id))

(defn resolve-variants
  [id]
  (get-in @fontsdb [id :variants]))

(defn resolve-fonts
  [backend]
  (get @fontsview backend))

;; --- Fonts Loader

(defonce loaded (l/atom #{}))

(defn- create-link-node
  [uri]
  (let [node (.createElement js/document "link")]
    (unchecked-set node "href" uri)
    (unchecked-set node "rel" "stylesheet")
    (unchecked-set node "type" "text/css")
    node))

(defn gfont-url [family variants]
  (let [base (str "https://fonts.googleapis.com/css?family=" family)
        variants (str/join "," (map :id variants))]
    (str base ":" variants "&display=block")))

(defmulti ^:private load-font :backend)

(defmethod load-font :builtin
  [{:keys [id ::on-loaded] :as font}]
  (js/console.log "[debug:fonts]: loading builtin font" id)
  (when (fn? on-loaded)
    (on-loaded id)))

(defmethod load-font :google
  [{:keys [id family variants ::on-loaded] :as font}]
  (when (exists? js/window)
    (js/console.log "[debug:fonts]: loading google font" id)
    (let [node (create-link-node (gfont-url family variants))]
      (.addEventListener node "load" (fn [event] (when (fn? on-loaded)
                                                   (on-loaded id))))
      (.append (.-head js/document) node)
      nil)))

(defmethod load-font :default
  [{:keys [backend] :as font}]
  (js/console.warn "no implementation found for" backend))

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

(defn ready [cb]
  (-> (obj/get-in js/document ["fonts" "ready"])
      (p/then cb)))

(defn get-default-variant [{:keys [variants]}]
  (or
   (d/seek #(or (= (:id %) "regular") (= (:name %) "regular")) variants)
   (first variants)))
