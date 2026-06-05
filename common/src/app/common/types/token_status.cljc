;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.token-status
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as c.json])
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.transit :as t]
   [app.common.types.tokens-lib :as ctob]
   [clojure.core.protocols :as cp]
   [clojure.datafy :refer [datafy]]
   [clojure.pprint :as pp]))

;; TokenStatus datatype contains the status of the active themes and sets
;; in a tokens library.

(defprotocol ITokenStatus
  (activate-theme [_ theme-id] "Add a theme uuid to active themes")
  (deactivate-theme [_ theme-id] "Remove a theme uuid from active themes")
  (toggle-theme-active [_ theme-id] "Toggle a theme uuid in active themes")
  (theme-active? [_ theme-id] "Check if a theme uuid is active")
  (active-theme-count [_] "Return the number of active themes")
  (activate-set [_ set-id] "Add a set uuid to active sets")
  (deactivate-set [_ set-id] "Remove a set uuid from active sets")
  (toggle-set-active [_ set-id] "Toggle a set uuid in active sets")
  (set-active? [_ set-id] "Check if a set uuid is active")
  (active-set-count [_] "Return the number of active sets"))

(deftype TokenStatus [active-themes active-sets]
  cp/Datafiable
  (datafy [_]
    {:active-themes active-themes
     :active-sets active-sets})

  #?@(:clj
      [c.json/JSONWriter
       (-write [this writter options]
               (c.json/-write (datafy this) writter options))])

  ITokenStatus
  (activate-theme [_ theme-id]
    (TokenStatus. (conj active-themes theme-id) active-sets))

  (deactivate-theme [_ theme-id]
    (TokenStatus. (disj active-themes theme-id) active-sets))

  (toggle-theme-active [this theme-id]
    (if (contains? active-themes theme-id)
      (deactivate-theme this theme-id)
      (activate-theme this theme-id)))

  (theme-active? [_ theme-id]
    (contains? active-themes theme-id))

  (active-theme-count [_]
    (prn active-themes)
    (count active-themes))

  (activate-set [_ set-id]
    (TokenStatus. active-themes (conj active-sets set-id)))

  (deactivate-set [_ set-id]
    (TokenStatus. active-themes (disj active-sets set-id)))

  (toggle-set-active [this set-id]
    (if (contains? active-sets set-id)
      (deactivate-set this set-id)
      (activate-set this set-id)))

  (set-active? [_ set-id]
    (contains? active-sets set-id))
  
  (active-set-count [_]
    (count active-sets)))

;; === Helper & Predicate ===

(defn map->TokenStatus
  [{:keys [active-themes active-sets]}]
  (TokenStatus. active-themes active-sets))

(defn token-status?
  [o]
  (instance? TokenStatus o))

;; === Schemas, Check functions & Constructor ===

(declare make-token-status)

(def schema:token-status-attrs
  [:map {:title "TokenStatus"}
   [:active-themes [:set {:gen/max 5} ::sm/uuid]]
   [:active-sets [:set {:gen/max 5} ::sm/uuid]]])

(def schema:token-status
  [:and {:gen/gen (->> (sg/generator schema:token-status-attrs)
                       (sg/fmap #(make-token-status %)))}
   [:fn token-status?]])

(def ^:private check-token-status-attrs
  (sm/check-fn schema:token-status-attrs
               :hint "expected valid params for token-status"))

(def check-token-status
  (sm/check-fn schema:token-status
               :hint "expected valid token-status"))

(defn make-token-status
  [& {:as attrs}]
  (-> attrs
      (update :active-themes #(or % #{}))
      (update :active-sets #(or % #{}))
      (check-token-status-attrs)
      (map->TokenStatus)))

(defn make-token-status-from-lib
  "Make a TokenStatus from a TokensLib, activating the themes and sets
   marked as active in the library (to migrate from legacy files)."
  [tokens-lib]
  (let [active-themes (into #{}
                            (comp (map :id)
                                  (filter #(not= % ctob/hidden-theme-id)))
                            (ctob/get-active-themes tokens-lib))
        active-sets   (into #{}
                            (comp (map #(ctob/get-set-by-name tokens-lib %))
                                  (map ctob/get-id))
                            (ctob/get-active-themes-set-names tokens-lib))]
    (make-token-status :active-themes active-themes
                        :active-sets active-sets)))

;; === Pretty-print for debugging ===

(defmethod pp/simple-dispatch TokenStatus [^TokenStatus obj]
  (.write *out* "#penpot/token-status ")
  (pp/pprint-newline :miser)
  (pp/pprint (datafy obj)))

#?(:clj
   (do
     (defmethod print-method TokenStatus
       [^TokenStatus this ^java.io.Writer w]
       (.write w "#penpot/token-status ")
       (print-method (datafy this) w))

     (defmethod print-dup TokenStatus
       [^TokenStatus this ^java.io.Writer w]
       (print-method this w)))

   :cljs
   (extend-type TokenStatus
     cljs.core/IPrintWithWriter
     (-pr-writer [this writer opts]
       (-write writer "#penpot/token-status ")
       (-pr-writer (datafy this) writer opts))

     cljs.core/IEncodeJS
     (-clj->js [this]
       (clj->js (datafy this)))))

;; === Transit serialization ===

(t/add-handlers!
 {:id "penpot/token-status"
  :class TokenStatus
  :wfn datafy
  :rfn #(make-token-status %)})

;; === Fressian serialization ===

#?(:clj
   (fres/add-handlers!
    {:name "penpot/token-status/v1"
     :class TokenStatus
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (datafy o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (make-token-status obj)))}))
