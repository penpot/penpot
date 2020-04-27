;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.fonts
  "Fonts management and loading logic."
  (:require-macros [uxbox.main.fonts :refer [preload-gfonts]])
  (:require
   [beicon.core :as rx]
   [promesa.core :as p]
   [okulary.core :as l]
   [cuerdas.core :as str]
   [uxbox.util.dom :as dom]
   [uxbox.util.timers :as ts]
   [uxbox.common.data :as d]
   [clojure.set :as set]))

(def google-fonts
  (preload-gfonts "fonts/gfonts.2020.04.23.json"))

(def local-fonts
  [{:id "sourcesanspro"
    :name "Source Sans Pro"
    :family "sourcesanspro"
    :variants [{:name "100" :weight "100" :style "normal"}
               {:name "100italic" :weight "100" :style "italic"}
               {:name "200" :weight "200" :style "normal"}
               {:name "200italic" :weight "200" :style "italic"}
               {:name "300" :weight "300" :style "normal"}
               {:name "300italic" :weight "300" :style "italic"}
               {:name "regular" :weight "400" :style "normal"}
               {:name "italic" :weight "400" :style "italic"}
               {:name "500" :weight "500" :style "normal"}
               {:name "500italic" :weight "500" :style "italic"}
               {:name "bold" :weight "bold" :style "normal"}
               {:name "bolditalic" :weight "bold" :style "italic"}
               {:name "black" :weight "900" :style "normal"}
               {:name "blackitalic" :weight "900" :style "italic"}]}
   {:id "roboto"
    :family "roboto"
    :name "Roboto"
    :variants [{:name "100" :weight "100" :style "normal"}
               {:name "100italic" :weight "100" :style "italic"}
               {:name "200" :weight "200" :style "normal"}
               {:name "200italic" :weight "200" :style "italic"}
               {:name "regular" :weight "400" :style "normal"}
               {:name "italic" :weight "400" :style "italic"}
               {:name "500" :weight "500" :style "normal"}
               {:name "500italic" :weight "500" :style "italic"}
               {:name "bold" :weight "bold" :style "normal"}
               {:name "bolditalic" :weight "bold" :style "italic"}
               {:name "black" :weight "900" :style "normal"}
               {:name "blackitalic" :weight "900" :style "italic"}]}
   {:id "robotocondensed"
    :family "robotocondensed"
    :name "Roboto Condensed"
    :variants [{:name "100" :weight "100" :style "normal"}
               {:name "100italic" :weight "100" :style "italic"}
               {:name "200" :weight "200" :style "normal"}
               {:name "200italic" :weight "200" :style "italic"}
               {:name "regular" :weight "400" :style "normal"}
               {:name "italic" :weight "400" :style "italic"}
               {:name "500" :weight "500" :style "normal"}
               {:name "500italic" :weight "500" :style "italic"}
               {:name "bold" :weight "bold" :style "normal"}
               {:name "bolditalic" :weight "bold" :style "italic"}
               {:name "black" :weight "900" :style "normal"}
               {:name "blackitalic" :weight "900" :style "italic"}]}])

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
             (ts/schedule-on-idle #(materialize-fontsview db))))

(defn register!
  [backend fonts]
  (let [fonts (map #(assoc % :backend backend) fonts)]
    (swap! fontsdb #(merge % (d/index-by :id fonts)))))

(register! :builtin local-fonts)
(register! :google google-fonts)

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

(defmulti ^:private load-font :backend)

(defmethod load-font :builtin
  [{:keys [id ::on-loaded] :as font}]
  (js/console.log "[debug:fonts]: loading builtin font" id)
  (when (fn? on-loaded)
    (on-loaded id)))

(defmethod load-font :google
  [{:keys [id family variants ::on-loaded] :as font}]
  (js/console.log "[debug:fonts]: loading google font" id)
  (let [base (str "https://fonts.googleapis.com/css?family=" family)
        variants (str/join "," (map :name variants))
        uri (str base ":" variants "&display=block")
        node (create-link-node uri)]
    (.addEventListener node "load" (fn [event] (when (fn? on-loaded)
                                                 (on-loaded id))))
    (.append (.-head js/document) node)
    nil))

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


