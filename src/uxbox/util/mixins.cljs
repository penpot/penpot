;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.mixins
  (:refer-clojure :exclude [concat])
  (:require [sablono.core :refer-macros [html]]
            [sablono.server :as server]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.dom.forms :as gforms]))

(extend-type cljs.core.UUID
  INamed
  (-name [this] (str this))
  (-namespace [_] ""))

(defn component
  [{:keys [render] :as spec}]
  {:pre [(ifn? render)]}
  (let [name (or (:name spec) (str (gensym "rum-")))
        mixins (or (:mixins spec) [])
        spec (dissoc spec :name :mixins :render)
        render' (fn [state]
                  [(apply render state (:rum/args state)) state])
        mixins (conj mixins spec)]
    (rum/build-ctor render' mixins name)))

(defn concat
  [& elements]
  (html
   (for [[i element] (map-indexed vector elements)]
     (rum/with-key element (str i)))))

(defn local
  ([]
   (rum/local {} :rum/local))
  ([initial]
   (rum/local initial :rum/local))
  ([initial key]
   (rum/local initial key)))

(def mount rum/mount)
(def static rum/static)
(def ref-node rum/ref-node)
(def dom-node rum/dom-node)
(def react rum/react)
(def reactive rum/reactive)
(def with-key rum/with-key)
(def render-html server/render)
(def render-static-html server/render-static)
