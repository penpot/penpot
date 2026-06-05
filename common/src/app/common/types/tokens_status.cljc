;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.tokens-status
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as c.json])
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.transit :as t]
   [clojure.core.protocols :as cp]
   [clojure.datafy :refer [datafy]]
   [clojure.pprint :as pp]))

;; TokensStatus datatype contains the activation status of the themes and sets
;; in a tokens library.

(defprotocol ITokensStatus
  (get-active-theme-ids [_] "Return a clojure set of active theme ids")
  (get-active-set-ids [_] "Return a clojure set of active set ids")
  (theme-active? [_ theme-id] "Check if a theme is active")
  (set-active? [_ set-id] "Check if a set is active")
  (set-tokens-status [_ theme-ids set-ids] "Set the activation status of the themes and sets"))

(deftype TokensStatus [active-theme-ids active-set-ids]
  cp/Datafiable
  (datafy [_]
    {:active-theme-ids active-theme-ids
     :active-set-ids active-set-ids})

  #?@(:clj
      [c.json/JSONWriter
       (-write [this writter options]
               (c.json/-write (datafy this) writter options))])

  ITokensStatus
  (get-active-theme-ids [_]
    active-theme-ids)

  (get-active-set-ids [_]
    active-set-ids)

  (theme-active? [_ theme-id]
    (assert (uuid? theme-id))
    (contains? active-theme-ids theme-id))

  (set-active? [_ set-id]
    (assert (uuid? set-id))
    (contains? active-set-ids set-id))

  (set-tokens-status [_ theme-ids set-ids]
    (assert (set? theme-ids))
    (assert (set? set-ids))
    (TokensStatus. theme-ids set-ids)))

;; === Helper & Predicate ===

(defn map->TokensStatus
  [{:keys [active-theme-ids active-set-ids]}]
  (TokensStatus. active-theme-ids active-set-ids))

(defn tokens-status?
  [o]
  (instance? TokensStatus o))

;; === Schemas, Check functions & Constructor ===

(declare make-tokens-status)

(def schema:tokens-status-attrs
  [:map {:title "TokensStatus"}
   [:active-theme-ids {:optional true} [:set {:gen/max 5} ::sm/uuid]]
   [:active-set-ids {:optional true} [:set {:gen/max 5} ::sm/uuid]]])

(def schema:tokens-status
  [:and {:gen/gen (->> (sg/generator schema:tokens-status-attrs)
                       (sg/fmap #(make-tokens-status %)))}
   [:fn tokens-status?]])

(def ^:private check-tokens-status-attrs
  (sm/check-fn schema:tokens-status-attrs
               :hint "expected valid params for tokens-status"))

(def check-tokens-status
  (sm/check-fn schema:tokens-status
               :hint "expected valid tokens-status"))

(defn make-tokens-status
  [& {:as attrs}]
  (-> attrs
      (update :active-theme-ids #(or % #{}))
      (update :active-set-ids #(or % #{}))
      (check-tokens-status-attrs)
      (map->TokensStatus)))

;; === Pretty-print for debugging ===

(defmethod pp/simple-dispatch TokensStatus [^TokensStatus obj]
  (.write *out* "#penpot/tokens-status ")
  (pp/pprint-newline :miser)
  (pp/pprint (datafy obj)))

#?(:clj
   (do
     (defmethod print-method TokensStatus
       [^TokensStatus this ^java.io.Writer w]
       (.write w "#penpot/tokens-status ")
       (print-method (datafy this) w))

     (defmethod print-dup TokensStatus
       [^TokensStatus this ^java.io.Writer w]
       (print-method this w)))

   :cljs
   (extend-type TokensStatus
     cljs.core/IPrintWithWriter
     (-pr-writer [this writer opts]
       (-write writer "#penpot/tokens-status ")
       (-pr-writer (datafy this) writer opts))

     cljs.core/IEncodeJS
     (-clj->js [this]
       (clj->js (datafy this)))))

;; === Transit serialization ===

(t/add-handlers!
 {:id "penpot/tokens-status"
  :class TokensStatus
  :wfn datafy
  :rfn #(make-tokens-status %)})

;; === Fressian serialization ===

#?(:clj
   (fres/add-handlers!
    {:name "penpot/tokens-status/v1"
     :class TokensStatus
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (datafy o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (make-tokens-status obj)))}))
