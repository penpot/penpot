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
   [app.util.object :as obj]))

(defn fetch-as-data-uri [url]
  (-> (js/fetch url)
      (p/then (fn [res] (.blob res)))
      (p/then (fn [blob]
                (let [reader (js/FileReader.)]
                  (p/create (fn [resolve reject]
                              (obj/set! reader "onload" #(resolve [url (.-result reader)]))
                              (.readAsDataURL reader blob))))))))
