;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.svg
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- map->css [attr]
  (->> attr
       (map (fn [[attr-key attr-value]] (str (d/name attr-key) ":" attr-value)))
       (str/join "; ")))

(mf/defc svg-panel*
  [{:keys [shape _objects]}]
  [:div {:class (stl/css :svg-panel)}
   [:div {:key (:id shape) :class (stl/css :svg-shape)}
    (for [[attr-key attr-value] (:svg-attrs shape)]
      (if (map? attr-value)
        (for [[sub-attr-key sub-attr-value] attr-value]
          (let [property-value (map->css sub-attr-value)]
            [:> properties-row* {:key (dm/str "svg-property-" (d/name sub-attr-key))
                                 :term (d/name sub-attr-key)
                                 :detail (dm/str sub-attr-value)
                                 :property property-value
                                 :copiable false}]))
        [:> properties-row* {:key (dm/str "svg-property-" (d/name attr-key))
                             :term (d/name attr-key)
                             :detail (dm/str attr-value)
                             :property (dm/str attr-key ": " attr-value ";")
                             :copiable false}]))]])
