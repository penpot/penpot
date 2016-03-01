;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.repo
  "A main interface for access to remote resources."
  (:refer-clojure :exclude [do])
  (:require [postal.client :as ps]
            [beicon.core :as rx]))

(def ^:private ^:const +client+
  "https://test.uxbox.io")

(defn novelty
  [type data]
  (rx/from-promise
   (ps/novelty +client+ type data)))

(defn query
  ([type]
   (rx/from-promise
    (ps/query +client+ type)))
  ([type data]
   (rx/from-promise
    (ps/query +client+ type data))))

(defmulti -do
  (fn [type data] type))

(defn do
  "Perform a side effectfull action accesing
  remote remote resources."
  ([type]
   (-do type nil))
  ([type data]
   (-do type data)))

;; (defmethod do :login
;;   [type data]
;;   (rx/from-promise
;;    (ps/novelty :login data)))

(defmethod -do :login
  [type data]
  (->> (rx/of {:fullname "Cirilla Fiona"
               :photo "/images/favicon.png"
               :username "cirilla"
               :email "cirilla@uxbox.io"})
       (rx/delay 100)))
