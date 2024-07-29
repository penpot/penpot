;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.assets.icon
  (:require
   [clojure.core :as c]
   [rumext.v2]))

(defmacro collect-icons []
  (let [ns-info (:ns &env)]
    `(set '~(->> (:defs ns-info)
                 (map val)
                 (filter (fn [entry] (-> entry :meta :icon-id)))
                 (map (fn [{:keys [name]}] (c/name name)))))))