;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.lens
  (:refer-clojure :exclude [derive merge])
  (:require [lentes.core :as l]))

(defn getter
  [f]
  (l/lens f #(throw (ex-info "Not implemented" {}))))

(defn merge
  [data]
  (l/lens
   (fn [s] (cljs.core/merge s data))
   #(throw (ex-info "Not implemented" {}))))
