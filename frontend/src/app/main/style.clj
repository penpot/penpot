;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.style
  "A fonts loading macros."
  (:require
   [app.common.data :as d]
   [clojure.data.json :as json]))

(defmacro css
  [selector]
  (let [;; Get the associated styles will be module.cljs => module.css.json
        filename    (:file (meta *ns*))
        styles-file (str "./src/" (subs filename 0 (- (count filename) 4)) "css.json")
        data        (-> (slurp styles-file)
                        (json/read-str))
        result (get data (d/name selector))]
    `~result))

(defmacro styles
  []
  (let [;; Get the associated styles will be module.cljs => module.css.json
        filename    (:file (meta *ns*))
        styles-file (str "./src/" (subs filename 0 (- (count filename) 4)) "css.json")
        data        (-> (slurp styles-file)
                        (json/read-str))
        data        (into {} (map (fn [[k v]] [(keyword k) v])) data)]
    `~data))