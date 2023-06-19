;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen.common
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]))

(defn shape->selector
  [shape]
  (let [name (-> (:name shape)
                 (subs 0 (min 10 (count (:name shape)))))
        ;; selectors cannot start with numbers
        name (if (re-matches #"^\d.*" name) (dm/str "c-" name) name)
        id (-> (dm/str (:id shape))
               #_(subs 24 36))
        selector (str/css-selector (dm/str name " " id))
        selector (if (str/starts-with? selector "-") (subs selector 1) selector)]
    selector))
