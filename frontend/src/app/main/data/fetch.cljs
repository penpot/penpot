;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.fetch
  (:require
   [promesa.core :as p]
   [potok.core :as ptk]
   [okulary.core :as l]
   [app.util.object :as obj]
   [app.main.store :as st]))

(defn pending-ref []
  (l/derived ::to-fetch st/state))

(defn add [to-fetch id]
  (let [to-fetch (or to-fetch (hash-set))]
    (conj to-fetch id)))

(defn fetch-as-data-uri [url]
  (let [id (random-uuid)]
    (st/emit! (fn [state] (update state ::to-fetch add id)))
    (-> (js/fetch url)
        (p/then (fn [res] (.blob res)))
        (p/then (fn [blob]
                  (let [reader (js/FileReader.)]
                    (p/create (fn [resolve reject]
                                (obj/set! reader "onload" #(resolve [url (.-result reader)]))
                                (.readAsDataURL reader blob))))))
        (p/finally #(st/emit! (fn [state] (update state ::to-fetch disj id)))))))
