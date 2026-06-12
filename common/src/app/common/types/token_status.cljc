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
   [clojure.pprint :as pp]
   [clojure.set :as set]))

;; TokenStatus datatype contains the status of the active themes and sets
;; in a tokens library.

(defprotocol ITokenStatus
  (activate-theme [_ tokens-lib theme-id] "Activate a theme and deactivate other themes in the same group. Update active sets.")
  (deactivate-theme [_ tokens-lib theme-id] "Deactivate a theme and update active sets")
  (set-theme-status [_ tokens-lib theme-id status] "Set the activation status of a theme")
  (theme-active? [_ theme-id] "Check if a theme is active")
  (active-themes-count [_] "Return the number of active themes")
  (activate-set [_ set-id] "Add a set to active sets")
  (deactivate-set [_ set-id] "Remove a set from active sets")
  (toggle-set-active [_ set-id] "Toggle a set in active sets")
  (set-active? [_ set-id] "Check if a set is active")
  (active-set-count [_] "Return the number of active sets"))

(declare calculate-active-sets)
(deftype TokenStatus [active-theme-ids active-set-ids]
  cp/Datafiable
  (datafy [_]
    {:active-theme-ids active-theme-ids
     :active-set-ids active-set-ids})

  #?@(:clj
      [c.json/JSONWriter
       (-write [this writter options]
               (c.json/-write (datafy this) writter options))])

  ITokenStatus
  (activate-theme [this tokens-lib theme-id]
    (assert (ctob/tokens-lib? tokens-lib) "expected valid tokens-lib")
    (assert (uuid? theme-id) "expected valid theme-id")
    (if-not (theme-active? this theme-id)
      (if-let [theme (ctob/get-theme tokens-lib theme-id)]
        (let [group-themes   (ctob/get-themes-in-group tokens-lib (:group theme))
              active-theme-ids' (-> (set/difference active-theme-ids group-themes)
                                 (conj theme-id))]
          (TokenStatus. active-theme-ids'
                        (calculate-active-sets active-theme-ids' tokens-lib)))
        this)
      this))

  (deactivate-theme [this tokens-lib theme-id]
    (assert (ctob/tokens-lib? tokens-lib) "expected valid tokens-lib")
    (assert (uuid? theme-id) "expected valid theme-id")
    (if (theme-active? this theme-id)
      (let [active-theme-ids' (disj active-theme-ids theme-id)]
        (TokenStatus. active-theme-ids'
                      (calculate-active-sets active-theme-ids' tokens-lib)))
      this))

  (set-theme-status [this tokens-lib theme-id status]
    (assert (ctob/tokens-lib? tokens-lib) "expected valid tokens-lib")
    (assert (uuid? theme-id) "expected valid theme-id")
    (assert (boolean? status) "expected boolean status")
    (if status
      (activate-theme this tokens-lib theme-id)
      (deactivate-theme this tokens-lib theme-id)))

  (theme-active? [_ theme-id]
    (assert (uuid? theme-id) "expected valid theme-id")
    (contains? active-theme-ids theme-id))

  (active-themes-count [_]
    (count active-theme-ids))

  (activate-set [_ set-id]
    (assert (uuid? set-id) "expected valid set-id")
    (TokenStatus. active-theme-ids (conj active-set-ids set-id)))

  (deactivate-set [_ set-id]
    (assert (uuid? set-id) "expected valid set-id")
    (TokenStatus. active-theme-ids (disj active-set-ids set-id)))

  (toggle-set-active [this set-id]
    (assert (uuid? set-id) "expected valid set-id")
    (if (contains? active-set-ids set-id)
      (deactivate-set this set-id)
      (activate-set this set-id)))

  (set-active? [_ set-id]
    (assert (uuid? set-id) "expected valid set-id")
    (contains? active-set-ids set-id))

  (active-set-count [_]
    (count active-set-ids)))

(defn- calculate-active-sets
  [active-theme-ids tokens-lib]
  (let [active-themes (map #(ctob/get-theme tokens-lib %) active-theme-ids)    ;; OJOOOOOOOOOOOOOOOOOOOOO
        active-set-names (reduce set/union #{} (map :sets active-themes))
        active-sets (map #(ctob/get-set-by-name tokens-lib %) active-set-names)
        active-set-ids (into #{} (map ctob/get-id) active-sets)]
    active-set-ids))

;; === Helper & Predicate ===

(defn map->TokenStatus
  [{:keys [active-theme-ids active-set-ids]}]
  (TokenStatus. active-theme-ids active-set-ids))

(defn token-status?
  [o]
  (instance? TokenStatus o))

;; === Schemas, Check functions & Constructor ===

(declare make-token-status)

(def schema:token-status-attrs
  [:map {:title "TokenStatus"}
   [:active-theme-ids [:set {:gen/max 5} ::sm/uuid]]
   [:active-set-ids [:set {:gen/max 5} ::sm/uuid]]])

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
      (update :active-theme-ids #(or % #{}))
      (update :active-set-ids #(or % #{}))
      (check-token-status-attrs)
      (map->TokenStatus)))

(defn make-token-status-from-lib
  "Make a TokenStatus from a TokensLib, activating the themes and sets
   marked as active in the library (to migrate from legacy files)."
  [tokens-lib]
  (assert (ctob/tokens-lib? tokens-lib) "expected valid tokens-lib")
  (let [active-theme-ids (into #{}
                               (comp (map :id)
                                     (filter #(not= % ctob/hidden-theme-id)))
                               (ctob/get-active-themes tokens-lib))
        active-set-ids   (into #{}
                               (comp (map #(ctob/get-set-by-name tokens-lib %))
                                     (map ctob/get-id))
                               (ctob/get-active-themes-set-names tokens-lib))]
    (make-token-status :active-theme-ids active-theme-ids
                       :active-set-ids active-set-ids)))

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
