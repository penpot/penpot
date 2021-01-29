;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.common.version
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]))

(def version-re #"^(([A-Za-z]+)\-?)?(\d+\.\d+\.\d+)(\-?((alpha|prealpha|beta|rc)(\d+)?))?(\-?(\d+))?(\-?(\w+))$")

(defn parse
  [data]
  (cond
    (= data "%version%")
    {:full "develop"
     :base "develop"
     :branch "develop"
     :modifier nil
     :commit nil
     :commit-hash nil}

    (string? data)
    (let [result (re-find version-re data)]
      {:full data
       :base (get result 3)
       :branch (get result 2)
       :modifier (get result 5)
       :commit   (get result 9)
       :commit-hash (get result 11)})

    :else nil))

