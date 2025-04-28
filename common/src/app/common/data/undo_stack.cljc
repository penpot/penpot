;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.data.undo-stack
  (:refer-clojure :exclude [peek])
  (:require
   #?(:cljs [cljs.core :as core]
      :clj [clojure.core :as core])))

(defonce MAX-UNDO-SIZE 100)

(defn make-stack
  []
  {:index -1
   :items []})

(defn peek
  [{index :index items :items :as stack}]
  (when (and (>= index 0) (< index (count items)))
    (nth items index)))

(defn append
  [{index :index items :items :as stack} value]

  (if (and (some? stack) (not= value (peek stack)))
    (let [items (cond-> items
                  (> index 0)
                  (subvec 0 (inc index))

                  (> (+ index 2) MAX-UNDO-SIZE)
                  (subvec 1 (inc index))

                  :always
                  (conj value))

          index (min (dec MAX-UNDO-SIZE) (inc index))]
      {:index index
       :items items})
    stack))

(defn fixup
  [{index :index :as stack} value]
  (assoc-in stack [:items index] value))

(defn undo
  [stack]
  (update stack :index #(max 0 (dec %))))

(defn redo
  [{index :index items :items :as stack}]
  (cond-> stack
    (< index (dec (count items)))
    (update :index inc)))

(defn size
  [{index :index :as stack}]
  (inc index))
