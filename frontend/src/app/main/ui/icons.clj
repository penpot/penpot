;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.icons
  (:require
   [clojure.core :as c]
   [cuerdas.core :as str]
   [rumext.v2]))

(def exceptions #{:penpot-logo-icon})

(defmacro icon-xref
  [id & [class]]
  (let [href (str "#icon-" (name id))
        class (or class (str "icon-" (name id)))]
    `(rumext.v2/html
      [:svg {:width 500 :height 500 :class ~class}
       [:use {:href ~href}]])))

(defmacro collect-icons
  []
  (let [ns-info (:ns &env)]
    `(cljs.core/js-obj
      ~@(->> (:defs ns-info)
             (map val)
             (filter (fn [entry] (-> entry :meta :icon)))
             (mapcat (fn [{:keys [name] :as entry}]
                       [(-> name c/name str/camel str/capital) name]))))))
